package org.opencv.android;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.util.Log;
import java.lang.reflect.Method;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/* loaded from: classes.dex */
public class JavaCameraView extends CameraBridgeViewBase implements Camera.PreviewCallback {
    public static final int HEIGHT = 480;
    private static final int MAGIC_TEXTURE_ID = 10;
    private static final String TAG = "JavaCameraView";
    public static final int WIDTH = 640;
    private boolean enableFaceDetection;
    private boolean enableQRCodeReading;
    private boolean enableVideoStreaming;
    private boolean hasFrame;
    private byte[] mBuffer;
    protected Camera mCamera;
    protected JavaCameraFrame[] mCameraFrame;
    private boolean mCameraFrameReady;
    private int mChainIdx;
    private Mat[] mFrameChain;
    private boolean mStopThread;
    private SurfaceTexture mSurfaceTexture;
    private Thread mThread;
    private QRCodeFrameCallback qrCodeFrameCallback;
    private VideoFrameCallback videoFrameCallback;

    public interface QRCodeFrameCallback {
        void onPreview(byte[] bArr, Camera camera);
    }

    public interface VideoFrameCallback {
        void onPreview(byte[] bArr, Camera camera);
    }

    public static class JavaCameraSizeAccessor implements CameraBridgeViewBase.ListItemAccessor {
        @Override // org.opencv.android.CameraBridgeViewBase.ListItemAccessor
        public int getWidth(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.width;
        }

        @Override // org.opencv.android.CameraBridgeViewBase.ListItemAccessor
        public int getHeight(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.height;
        }
    }

    public JavaCameraView(Context context, int cameraId) {
        super(context, cameraId);
        this.mChainIdx = 0;
        this.enableFaceDetection = false;
        this.enableVideoStreaming = false;
        this.enableQRCodeReading = false;
        this.hasFrame = false;
        this.mCameraFrameReady = false;
    }

    public JavaCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.mChainIdx = 0;
        this.enableFaceDetection = false;
        this.enableVideoStreaming = false;
        this.enableQRCodeReading = false;
        this.hasFrame = false;
        this.mCameraFrameReady = false;
    }

    /* JADX WARN: Removed duplicated region for block: B:30:0x0083  */
    /* JADX WARN: Removed duplicated region for block: B:34:0x01c2 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    protected boolean initializeCamera(int r24, int r25) {
        /*
            Method dump skipped, instructions count: 1148
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: org.opencv.android.JavaCameraView.initializeCamera(int, int):boolean");
    }

    protected void setDisplayOrientation(Camera camera, int angle) {
        try {
            Method downPolymorphic = camera.getClass().getMethod("setDisplayOrientation", Integer.TYPE);
            if (downPolymorphic != null) {
                downPolymorphic.invoke(camera, Integer.valueOf(angle));
            }
        } catch (Exception e) {
        }
    }

    public boolean hasFrame() {
        return this.hasFrame;
    }

    public void setHasFrame(boolean hasFrame) {
        this.hasFrame = hasFrame;
    }

    public Camera getCamera() {
        return this.mCamera;
    }

    public void enableFaceDetection(boolean enableFaceDetection) {
        this.enableFaceDetection = enableFaceDetection;
    }

    public void enableVideoStreaming(boolean enableVideoStreaming) {
        this.enableVideoStreaming = enableVideoStreaming;
    }

    public void setVideoFrameCallback(VideoFrameCallback videoFrameCallback) {
        this.videoFrameCallback = videoFrameCallback;
    }

    public void enableQRCodeReading(boolean enableQRCodeReading) {
        this.enableQRCodeReading = enableQRCodeReading;
    }

    public void setQRCodeFrameCallback(QRCodeFrameCallback qrCodeFrameCallback) {
        this.qrCodeFrameCallback = qrCodeFrameCallback;
    }

    protected void releaseCamera() {
        synchronized (this) {
            if (this.mCamera != null) {
                this.mCamera.setPreviewCallback(null);
                this.mCamera.stopPreview();
                this.mCamera.release();
            }
            this.mCamera = null;
            if (this.mFrameChain != null) {
                this.mFrameChain[0].release();
                this.mFrameChain[1].release();
            }
            if (this.mCameraFrame != null) {
                this.mCameraFrame[0].release();
                this.mCameraFrame[1].release();
            }
        }
    }

    @Override // org.opencv.android.CameraBridgeViewBase
    protected boolean connectCamera(int width, int height) {
        Log.d(TAG, "Connecting to camera");
        if (!initializeCamera(width, height)) {
            return false;
        }
        this.mCameraFrameReady = false;
        Log.d(TAG, "Starting processing thread");
        this.mStopThread = false;
        this.mThread = new Thread(new CameraWorker());
        this.mThread.start();
        return true;
    }

    @Override // org.opencv.android.CameraBridgeViewBase
    protected void disconnectCamera() {
        Log.d(TAG, "Disconnecting from camera");
        try {
            this.mStopThread = true;
            Log.d(TAG, "Notify thread");
            synchronized (this) {
                notify();
            }
            Log.d(TAG, "Wating for thread");
            if (this.mThread != null) {
                this.mThread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            this.mThread = null;
        }
        releaseCamera();
        this.mCameraFrameReady = false;
    }

    @Override // android.hardware.Camera.PreviewCallback
    public void onPreviewFrame(byte[] frame, Camera arg1) {
        this.hasFrame = true;
        synchronized (this) {
            if (this.enableFaceDetection) {
                this.mFrameChain[this.mChainIdx].put(0, 0, frame);
                this.mCameraFrameReady = true;
                notify();
            }
            if (this.enableVideoStreaming) {
                this.videoFrameCallback.onPreview(frame, arg1);
            }
            if (this.enableQRCodeReading) {
                this.qrCodeFrameCallback.onPreview(frame, arg1);
            }
        }
        if (this.mCamera != null) {
            this.mCamera.addCallbackBuffer(this.mBuffer);
        }
    }

    private class JavaCameraFrame implements CameraBridgeViewBase.CvCameraViewFrame {
        private int mHeight;
        private Mat mRgba = new Mat();
        private int mWidth;
        private Mat mYuvFrameData;

        @Override // org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame
        public Mat gray() {
            return this.mYuvFrameData.submat(0, this.mHeight, 0, this.mWidth);
        }

        @Override // org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame
        public Mat rgba() {
            Imgproc.cvtColor(this.mYuvFrameData, this.mRgba, 96, 4);
            return this.mRgba;
        }

        public JavaCameraFrame(Mat Yuv420sp, int width, int height) {
            this.mWidth = width;
            this.mHeight = height;
            this.mYuvFrameData = Yuv420sp;
        }

        public void release() {
            this.mRgba.release();
        }
    }

    private class CameraWorker implements Runnable {
        private CameraWorker() {
        }

        @Override // java.lang.Runnable
        public void run() {
            do {
                boolean hasFrame = false;
                synchronized (JavaCameraView.this) {
                    while (!JavaCameraView.this.mCameraFrameReady && !JavaCameraView.this.mStopThread) {
                        try {
                            JavaCameraView.this.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    if (JavaCameraView.this.mCameraFrameReady) {
                        JavaCameraView.this.mChainIdx = 1 - JavaCameraView.this.mChainIdx;
                        JavaCameraView.this.mCameraFrameReady = false;
                        hasFrame = true;
                    }
                }
                if (!JavaCameraView.this.mStopThread && hasFrame && !JavaCameraView.this.mFrameChain[1 - JavaCameraView.this.mChainIdx].empty()) {
                    JavaCameraView.this.deliverAndDrawFrame(JavaCameraView.this.mCameraFrame[1 - JavaCameraView.this.mChainIdx]);
                }
            } while (!JavaCameraView.this.mStopThread);
            JavaCameraView.this.hasFrame = false;
            Log.d(JavaCameraView.TAG, "Finish processing thread");
        }
    }
}
