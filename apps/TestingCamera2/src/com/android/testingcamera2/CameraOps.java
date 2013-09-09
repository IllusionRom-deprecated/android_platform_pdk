/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.testingcamera2;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CameraProperties;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.Size;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A camera controller class that runs in its own thread, to
 * move camera ops off the UI. Generally thread-safe.
 */
public class CameraOps {

    private static final String TAG = "CameraOps";

    private Thread mOpsThread;
    private Handler mOpsHandler;

    private CameraManager mCameraManager;
    private CameraDevice mCamera;

    private ImageReader mCaptureReader;
    private CameraProperties mCameraProperties;

    private int mEncodingBitRate;

    private CaptureRequest mPreviewRequest;
    private CaptureRequest mRecordingRequest;
    List<Surface> mOutputSurfaces = new ArrayList<Surface>(2);
    private Surface mPreviewSurface;
    // How many JPEG buffers do we want to hold on to at once
    private static final int MAX_CONCURRENT_JPEGS = 2;

    private static final int STATUS_ERROR = 0;
    private static final int STATUS_UNINITIALIZED = 1;
    private static final int STATUS_OK = 2;
    // low encoding bitrate(bps), used by small resolution like 640x480.
    private static final int ENC_BIT_RATE_LOW = 2000000;
    // high encoding bitrate(bps), used by large resolution like 1080p.
    private static final int ENC_BIT_RATE_HIGH = 10000000;
    private static final Size DEFAULT_SIZE = new Size(640, 480);
    private static final Size HIGH_RESOLUTION_SIZE = new Size(1920, 1080);

    private int mStatus = STATUS_UNINITIALIZED;

    CameraRecordingStream mRecordingStream;

    private void checkOk() {
        if (mStatus < STATUS_OK) {
            throw new IllegalStateException(String.format("Device not OK: %d", mStatus ));
        }
    }

    private class OpsHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {

        }
    }

    private CameraOps(Context ctx) throws ApiFailureException {
        mCameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (mCameraManager == null) {
            throw new ApiFailureException("Can't connect to camera manager!");
        }

        mOpsThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mOpsHandler = new OpsHandler();
                Looper.loop();
            }
        }, "CameraOpsThread");
        mOpsThread.start();

        mRecordingStream = new CameraRecordingStream();
        mStatus = STATUS_OK;
    }

    static public CameraOps create(Context ctx) throws ApiFailureException {
        return new CameraOps(ctx);
    }

    public String[] getDevices() throws ApiFailureException{
        checkOk();
        try {
            return mCameraManager.getDeviceIdList();
        } catch (CameraAccessException e) {
            throw new ApiFailureException("Can't query device set", e);
        }
    }

    public void registerCameraListener(CameraManager.CameraListener listener)
            throws ApiFailureException {
        checkOk();
        mCameraManager.registerCameraListener(listener);
    }

    public CameraProperties getCameraProperties() {
        checkOk();
        if (mCameraProperties == null) {
            throw new IllegalStateException("CameraProperties is not available");
        }
        return mCameraProperties;
    }

    public void openDevice(String cameraId)
            throws CameraAccessException, ApiFailureException {
        checkOk();

        if (mCamera != null) {
            throw new IllegalStateException("Already have open camera device");
        }

        mCamera = mCameraManager.openCamera(cameraId);
    }

    public void closeDevice()
            throws ApiFailureException {
        checkOk();
        mCameraProperties = null;

        if (mCamera == null) return;

        try {
            mCamera.close();
        } catch (Exception e) {
            throw new ApiFailureException("can't close device!", e);
        }

        mCamera = null;
    }

    private void minimalOpenCamera() throws ApiFailureException {
        if (mCamera == null) {
            try {
                String[] devices = mCameraManager.getDeviceIdList();
                if (devices == null || devices.length == 0) {
                    throw new ApiFailureException("no devices");
                }
                mCamera = mCameraManager.openCamera(devices[0]);
                mCameraProperties = mCamera.getProperties();
            } catch (CameraAccessException e) {
                throw new ApiFailureException("open failure", e);
            }
        }

        mStatus = STATUS_OK;
    }

    /**
     * Set up SurfaceView dimensions for camera preview
     */
    public void minimalPreviewConfig(SurfaceHolder previewHolder) throws ApiFailureException {

        minimalOpenCamera();
        try {
            CameraProperties properties = mCamera.getProperties();

            Size[] previewSizes = null;
            Size sz = DEFAULT_SIZE;
            if (properties != null) {
                previewSizes = properties.get(
                        CameraProperties.SCALER_AVAILABLE_PROCESSED_SIZES);
            }

            if (previewSizes != null && previewSizes.length != 0 &&
                    Arrays.asList(previewSizes).contains(HIGH_RESOLUTION_SIZE)) {
                sz = HIGH_RESOLUTION_SIZE;
            }
            Log.i(TAG, "Set preview size to " + sz.toString());
            previewHolder.setFixedSize(sz.getWidth(), sz.getHeight());
            mPreviewSurface = previewHolder.getSurface();
        }  catch (CameraAccessException e) {
            throw new ApiFailureException("Error setting up minimal preview", e);
        }
    }


    /**
     * Update current preview with manual control inputs.
     */
    public void updatePreview(CameraControls manualCtrl) {
        updateCaptureRequest(mPreviewRequest, manualCtrl);

        try {
            // TODO: add capture result listener
            mCamera.setRepeatingRequest(mPreviewRequest, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Update camera preview failed");
        }
    }

    /**
     * Configure streams and run minimal preview
     */
    public void minimalPreview(SurfaceHolder previewHolder) throws ApiFailureException {

        minimalOpenCamera();
        if (mPreviewSurface == null) {
            throw new ApiFailureException("Preview surface is not created");
        }
        try {
            mCamera.stopRepeating();
            mCamera.waitUntilIdle();

            List<Surface> outputSurfaces = new ArrayList(1);
            outputSurfaces.add(mPreviewSurface);

            mCamera.configureOutputs(outputSurfaces);

            mPreviewRequest = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            mPreviewRequest.addTarget(mPreviewSurface);

            mCamera.setRepeatingRequest(mPreviewRequest, null);
        } catch (CameraAccessException e) {
            throw new ApiFailureException("Error setting up minimal preview", e);
        }
    }

    public void minimalJpegCapture(final CaptureListener listener, CaptureResultListener l,
            Handler h, CameraControls cameraControl) throws ApiFailureException {
        minimalOpenCamera();

        try {
            mCamera.stopRepeating();
            mCamera.waitUntilIdle();

            CameraProperties properties = mCamera.getProperties();
            Size[] jpegSizes = null;
            if (properties != null) {
                jpegSizes = properties.get(
                        CameraProperties.SCALER_AVAILABLE_JPEG_SIZES);
            }
            int width = 640;
            int height = 480;

            if (jpegSizes != null && jpegSizes.length > 0) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }

            if (mCaptureReader == null || mCaptureReader.getWidth() != width ||
                    mCaptureReader.getHeight() != height) {
                if (mCaptureReader != null) {
                    mCaptureReader.close();
                }
                mCaptureReader = new ImageReader(width, height,
                        ImageFormat.JPEG, MAX_CONCURRENT_JPEGS);
            }

            List<Surface> outputSurfaces = new ArrayList(1);
            outputSurfaces.add(mCaptureReader.getSurface());

            mCamera.configureOutputs(outputSurfaces);

            CaptureRequest captureRequest =
                    mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            captureRequest.addTarget(mCaptureReader.getSurface());

            updateCaptureRequest(captureRequest, cameraControl);

            ImageReader.OnImageAvailableListener readerListener =
                    new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image i = reader.getNextImage();
                    listener.onCaptureAvailable(i);
                    i.close();
                }
            };
            mCaptureReader.setImageAvailableListener(readerListener, h);

            mCamera.capture(captureRequest, l);

        } catch (CameraAccessException e) {
            throw new ApiFailureException("Error in minimal JPEG capture", e);
        }
    }

    public void startRecording(boolean useMediaCodec) throws ApiFailureException {
        minimalOpenCamera();
        Size recordingSize = getRecordingSize();
        CaptureRequest request;
        try {
            if (mRecordingRequest == null) {
                mRecordingRequest = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            }
            // Setup output stream first
            mRecordingStream.configure(recordingSize, useMediaCodec, mEncodingBitRate);
            mRecordingStream.onConfiguringOutputs(mOutputSurfaces, /* detach */false);
            mRecordingStream.onConfiguringRequest(mRecordingRequest, /* detach */false);

            // TODO: For preview, create preview stream class, and do the same thing like recording.
            mOutputSurfaces.add(mPreviewSurface);
            mRecordingRequest.addTarget(mPreviewSurface);

            // Start camera streaming and recording.
            mCamera.configureOutputs(mOutputSurfaces);
            mCamera.setRepeatingRequest(mRecordingRequest, null);
            mRecordingStream.start();
        } catch (CameraAccessException e) {
            throw new ApiFailureException("Error start recording", e);
        }
    }

    public void stopRecording() throws ApiFailureException {
        try {
            /**
             * <p>
             * Only stop camera recording stream.
             * </p>
             * <p>
             * FIXME: There is a race condition to be fixed in CameraDevice.
             * Basically, when stream closes, encoder and its surface is
             * released, while it still takes some time for camera to finish the
             * output to that surface. Then it cause camera in bad state.
             * </p>
             */
            mRecordingStream.onConfiguringRequest(mRecordingRequest, /* detach */true);
            mRecordingStream.onConfiguringOutputs(mOutputSurfaces, /* detach */true);
            mCamera.stopRepeating();
            mCamera.waitUntilIdle();
            mRecordingStream.stop();

            mCamera.configureOutputs(mOutputSurfaces);
            mCamera.setRepeatingRequest(mRecordingRequest, null);
        } catch (CameraAccessException e) {
            throw new ApiFailureException("Error stop recording", e);
        }
    }

    private Size getRecordingSize() throws ApiFailureException {
        try {
            CameraProperties properties = mCamera.getProperties();

            Size[] recordingSizes = null;
            if (properties != null) {
                recordingSizes = properties.get(
                        CameraProperties.SCALER_AVAILABLE_PROCESSED_SIZES);
            }

            mEncodingBitRate = ENC_BIT_RATE_LOW;
            if (recordingSizes == null || recordingSizes.length == 0) {
                Log.w(TAG, "Unable to get recording sizes, default to 640x480");
                return DEFAULT_SIZE;
            } else {
                /**
                 * TODO: create resolution selection widget on UI, then use the
                 * select size. For now, return HIGH_RESOLUTION_SIZE if it
                 * exists in the processed size list, otherwise return default
                 * size
                 */
                if (Arrays.asList(recordingSizes).contains(HIGH_RESOLUTION_SIZE)) {
                    mEncodingBitRate = ENC_BIT_RATE_HIGH;
                    return HIGH_RESOLUTION_SIZE;
                } else {
                    // Fallback to default size when HD size is not found.
                    Log.w(TAG,
                            "Unable to find the requested size " + HIGH_RESOLUTION_SIZE.toString()
                            + " Fallback to " + DEFAULT_SIZE.toString());
                    return DEFAULT_SIZE;
                }
            }
        } catch (CameraAccessException e) {
            throw new ApiFailureException("Error setting up video recording", e);
        }
    }

    private void updateCaptureRequest(CaptureRequest request, CameraControls cameraControl) {
        if (cameraControl != null) {
            // Update the manual control metadata for capture request
            // Disable 3A routines.
            if (cameraControl.isManualControlEnabled()) {
                Log.e(TAG, "update request: " + cameraControl.getSensitivity());
                request.set(CaptureRequest.CONTROL_MODE,
                        CameraMetadata.CONTROL_MODE_OFF);
                request.set(CaptureRequest.SENSOR_SENSITIVITY,
                        cameraControl.getSensitivity());
                request.set(CaptureRequest.SENSOR_FRAME_DURATION,
                        cameraControl.getFrameDuration());
                request.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                        cameraControl.getExposure());
            } else {
                request.set(CaptureRequest.CONTROL_MODE,
                        CameraMetadata.CONTROL_MODE_AUTO);
            }
        }
    }

    public interface CaptureListener {
        void onCaptureAvailable(Image capture);
    }

    public interface CaptureResultListener extends CameraDevice.CaptureListener {}
}