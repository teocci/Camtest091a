/* Copyright 2013 Foxdog Studios Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kseek.camjpeg;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;

import com.kseek.camjpeg.net.http.MJpegHttpStreamer;
import com.kseek.camjpeg.utils.Utilities;

import java.io.IOException;
import java.util.List;

import static android.hardware.Camera.PreviewCallback;
import static android.hardware.Camera.open;
import static com.kseek.camjpeg.utils.Utilities.LogE;
import static com.kseek.camjpeg.utils.Utilities.LogV;

final class CameraStreamer extends Object
{
    private static final String TAG = CameraStreamer.class.getSimpleName();

    private static final int MESSAGE_TRY_START_STREAMING = 0;
    private static final int MESSAGE_SEND_PREVIEW_FRAME = 1;

    private static final long OPEN_CAMERA_POLL_INTERVAL_MS = 1000L;

    private final Object lock = new Object();
    private final MovingAverage averageSpf = new MovingAverage(50); /* numValues */

    private final int cameraIndex;
    private final int httpPort;
    private final int previewSizeIndex;
    private final int jpegQuality;

    private StreamCameraActivity mainActivity;
    private SurfaceHolder previewDisplay;
    private Looper looper = null;
    private Handler workHandler = null;
    private Camera camera = null;
    private Rect previewRect = null;
    //private int width = Integer.MIN_VALUE;
    //private int height = Integer.MIN_VALUE;

    private Utilities.Sized prefSize;
    private Utilities.Sized screenSize;

    private boolean useFlashLight;
    private boolean running = false;

    private MemoryOutputStream jpegOutputStream = null;
    private MJpegHttpStreamer jpegHttpStreamer = null;

    private int previewBufferSize = Integer.MIN_VALUE;
    private int previewFormat = Integer.MIN_VALUE;
    private int previewWidth = Integer.MIN_VALUE;
    private int previewHeight = Integer.MIN_VALUE;

    private long numFrames = 0L;
    private long lastTimestamp = Long.MIN_VALUE;

    public CameraStreamer(final int cameraIndex,
                          final boolean useFlashLight,
                          final int httpPort,
                          final int previewSizeIndex,
                          final int jpegQuality,
                          final SurfaceHolder previewDisplay,
                          final Utilities.Sized prefSize,
                          final Utilities.Sized screenSize,
                          StreamCameraActivity mainActivity)
    {
        super();

        if (previewDisplay == null) {
            throw new IllegalArgumentException("previewDisplay must not be null");
        }

        this.cameraIndex = cameraIndex;
        this.useFlashLight = useFlashLight;
        this.httpPort = httpPort;
        this.previewSizeIndex = previewSizeIndex;
        this.jpegQuality = jpegQuality;
        this.previewDisplay = previewDisplay;
        this.prefSize = prefSize;
        this.screenSize = screenSize;
        this.mainActivity = mainActivity;
    }

    private final class WorkHandler extends Handler
    {
        private WorkHandler(final Looper looper)
        {
            super(looper);
        } // constructor(Looper)

        @Override
        public void handleMessage(final Message message)
        {
            try {
                switch (message.what) {
                    case MESSAGE_TRY_START_STREAMING:
                        tryStartStreaming();

                        break;
                    case MESSAGE_SEND_PREVIEW_FRAME:
                        final Object[] args = (Object[]) message.obj;
                        sendPreviewFrame((byte[]) args[0], (Camera) args[1], (Long) args[2]);
                        break;
                    default:
                        throw new IllegalArgumentException("cannot handle message");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void start()
    {
        synchronized (lock) {
            if (running) {
                throw new IllegalStateException("CameraStreamer is already running");
            }
            running = true;
        }

        final HandlerThread worker = new HandlerThread(TAG, Process.THREAD_PRIORITY_MORE_FAVORABLE);
        worker.setDaemon(true);
        worker.start();
        looper = worker.getLooper();
        workHandler = new WorkHandler(looper);
        workHandler.obtainMessage(MESSAGE_TRY_START_STREAMING).sendToTarget();
    }

    /**
     * Stop the image streamer. The camera will be released during the
     * execution of stop() or shortly after it returns. stop() should
     * be called on the main thread.
     */
    public void stop()
    {
        synchronized (lock) {
            if (!running) {
                throw new IllegalStateException("CameraStreamer is already stopped");
            }

            running = false;
            if (jpegHttpStreamer != null) {
                jpegHttpStreamer.stop();
            }
            if (camera != null) {
                camera.release();
                camera = null;
            }
        }
        looper.quit();
    }

    private void tryStartStreaming() throws InterruptedException
    {
        try {
            startStreamingIfRunning();
        } catch (final RuntimeException openCameraFailed) {
            LogV("Open camera failed, retying in "
                    + OPEN_CAMERA_POLL_INTERVAL_MS
                    + "ms | " + openCameraFailed);
            Thread.sleep(OPEN_CAMERA_POLL_INTERVAL_MS);
        } catch (final Exception startPreviewFailed) {
            // Captures the IOException from startStreamingIfRunning and
            // the InterruptException from Thread.sleep.
            LogE("Failed to start camera preview | " + startPreviewFailed);
        }
    }



    private void startStreamingIfRunning() throws IOException
    {
        // Throws RuntimeException if the camera is currently opened by another application.

        LogV("cameraIndex: " + cameraIndex);
        final Camera rawCamera = open(cameraIndex);
        final Camera.Parameters params = rawCamera.getParameters();

        final List<Camera.Size> supportedPreviewSizes = params.getSupportedPreviewSizes();
        //final Camera.Size selectedPreviewSize = supportedPreviewSizes.get(previewSizeIndex);

        final Camera.Size selectedPreviewSize =
                getOptimalPreviewSize(supportedPreviewSizes, prefSize);

        //final Camera.Size selectedPreviewSize = supportedPreviewSizes.get(previewSizeIndex);
        //params.setPreviewSize(width, height);

        params.setPreviewSize(
                selectedPreviewSize.width,
                selectedPreviewSize.height);

        rawCamera.setParameters(params);

        if (useFlashLight) {
            params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        }

        // Set Preview FPS range. The range with the greatest maximum is returned first.
        final List<int[]> supportedPreviewFpsRanges = params.getSupportedPreviewFpsRange();

        // Sometimes it returns null. This is a known bug
        // https://code.google.com/p/android/issues/detail?id=6271
        // In which case, we just don't set it.

        if (supportedPreviewFpsRanges != null) {
            final int[] range = supportedPreviewFpsRanges.get(0);
            params.setPreviewFpsRange(
                    range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                    range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
            rawCamera.setParameters(params);
        }

        // Set up preview callback
        previewFormat = params.getPreviewFormat();

        final Camera.Size previewSize = params.getPreviewSize();
        previewWidth = previewSize.width;
        previewHeight = previewSize.height;

        final Utilities.Sized full = getFullSize(screenSize, prefSize);

        //mainActivity.setFixedSize(full.width, full.height);

        /*LogV("firstPreviewSize >> w: " + firstPreviewSize.width + " h: " + firstPreviewSize
        .height);
        LogV("selectedPreviewSize >> w: " + selectedPreviewSize.width + " h: " +
                selectedPreviewSize.height);
        LogV("previewSize >> w: " + previewSize.width + " h: " + previewSize.height);*/

        final int BITS_PER_BYTE = 8;
        final int bytesPerPixel = ImageFormat.getBitsPerPixel(previewFormat) / BITS_PER_BYTE;

        // Note: According to the documentation the buffer size can be calculated by:
        // width * height * bytesPerPixel.
        // However, this returned an error saying it was too small. It always needed to be
        // exactly 1.5 times larger.
        previewBufferSize = previewWidth * previewHeight * bytesPerPixel * 3 / 2;
        rawCamera.addCallbackBuffer(new byte[previewBufferSize]);

        previewRect = new Rect(0, 0, previewWidth, previewHeight);
        rawCamera.setPreviewCallbackWithBuffer(previewCallback);

        // We assumed that the compressed image will be no bigger than the uncompressed image.
        jpegOutputStream = new MemoryOutputStream(previewBufferSize);

        final MJpegHttpStreamer streamer = new MJpegHttpStreamer(httpPort, previewBufferSize);
        streamer.start();

        synchronized (lock) {
            if (!running) {
                streamer.stop();
                rawCamera.release();
                return;
            }

            try {
                rawCamera.setPreviewDisplay(previewDisplay);
            } catch (final IOException e) {
                streamer.stop();
                rawCamera.release();
                throw e;
            }

            jpegHttpStreamer = streamer;
            rawCamera.startPreview();
            camera = rawCamera;
        }
    }

    private Utilities.Sized getFullSize(Utilities.Sized targetSize, Utilities.Sized baseSize)
    {
        double targetRatio = (double) baseSize.width / baseSize.height;
        //LogV("targetRatio: " + targetRatio);

        int targetHeight = targetSize.height;

        int targetWidth = (int) (targetRatio * targetHeight);

        //LogV("getFullSize >> w: " + targetWidth + " h: " + targetHeight);

        return new Utilities.Sized(targetWidth, targetHeight);
    }

    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, Utilities.Sized pref)
    {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) pref.height / pref.width;

        if (sizes == null) return null;

        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = pref.height;

        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            LogV("getOptimalPreviewSize >> w: " + size.width + " x h: " + size.height);
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    private final PreviewCallback previewCallback = new PreviewCallback()
    {
        @Override
        public void onPreviewFrame(final byte[] data, final Camera camera)
        {
            final Long timestamp = SystemClock.elapsedRealtime();
            final Message message = workHandler.obtainMessage();
            message.what = MESSAGE_SEND_PREVIEW_FRAME;
            message.obj = new Object[]{data, camera, timestamp};
            message.sendToTarget();
        }
    };

    private void sendPreviewFrame(final byte[] data, final Camera camera, final long timestamp)
    {
        // Calculate the timestamp
        final long MILLI_PER_SECOND = 1000L;
        final long timestampSeconds = timestamp / MILLI_PER_SECOND;

        // Update and log the frame rate
        final long LOGS_PER_FRAME = 5L;
        numFrames++;
        if (lastTimestamp != Long.MIN_VALUE) {
            averageSpf.update(timestampSeconds - lastTimestamp);
            if (numFrames % LOGS_PER_FRAME == LOGS_PER_FRAME - 1) {
                Log.d(TAG, "FPS: " + 1.0 / averageSpf.getAverage());
            }
        }

        lastTimestamp = timestampSeconds;
        // Create JPEG
        final YuvImage image = new YuvImage(data, previewFormat, previewWidth, previewHeight, null);
        image.compressToJpeg(previewRect, jpegQuality, jpegOutputStream);

        jpegHttpStreamer.streamJpeg(jpegOutputStream.getBuffer(), jpegOutputStream.getLength(),
                timestamp);

        // Clean up
        jpegOutputStream.seek(0);
        // XXX: I believe that this is thread-safe because we're not calling methods in other
        // threads.
        // I might be wrong, the documentation is not clear.
        camera.addCallbackBuffer(data);
    }
}

