package se.bitcraze.crazyfliecontrol2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Worker đọc luồng MJPEG trên background thread riêng, tự động giải mã frame ảnh
 * và tính toán FPS. Áp dụng nguyên tắc Latest Frame không dùng hàng đợi để chống trễ (zero latency).
 * Hỗ trợ pipeline xử lý hình ảnh OpenCV (ColorDetector) trước khi hiển thị.
 */
public class CameraStreamWorker {
    private static final String TAG = "CameraStreamWorker";

    public static final String STATE_DISCONNECTED = "DISCONNECTED";
    public static final String STATE_CONNECTING = "CONNECTING";
    public static final String STATE_STREAMING = "STREAMING";
    public static final String STATE_RECONNECTING = "RECONNECTING";

    public interface Listener {
        void onStateChanged(String state);
        void onFrame(Bitmap bitmap, float fps);
        void onError(String message);
        default void onDetections(List<ColorDetector.Detection> detections) {}
    }

    private final String mStreamUrl;
    private final int mRetryMs;
    private final int mTimeoutMs;
    private final Listener mListener;

    private final ColorDetector mDetector = new ColorDetector();
    private volatile boolean mDetectionEnabled = false;

    private volatile boolean mStop;
    private Thread mThread;
    private String mState = STATE_DISCONNECTED;
    private volatile HttpURLConnection mActiveConn;
    private volatile InputStream mActiveIn;

    public void setDetectionEnabled(boolean enabled) {
        mDetectionEnabled = enabled;
    }

    public boolean isDetectionEnabled() {
        return mDetectionEnabled;
    }

    public ColorDetector getDetector() {
        return mDetector;
    }

    public CameraStreamWorker(String streamUrl, Listener listener) {
        this(streamUrl, 1000, 5000, listener);
    }

    public CameraStreamWorker(String streamUrl, int retryMs, int timeoutMs, Listener listener) {
        mStreamUrl = streamUrl;
        mRetryMs = retryMs;
        mTimeoutMs = timeoutMs;
        mListener = listener;
    }

    public synchronized void start() {
        if (mThread != null && mThread.isAlive()) {
            return;
        }
        mStop = false;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "camera-stream-worker");
        mThread.setDaemon(true);
        mThread.start();
    }

    public synchronized void stop() {
        mStop = true;
        HttpURLConnection conn = mActiveConn;
        InputStream in = mActiveIn;
        if (in != null) {
            try { in.close(); } catch (Exception ignored) {}
        }
        if (conn != null) {
            try { conn.disconnect(); } catch (Exception ignored) {}
        }
        if (mThread != null) {
            mThread.interrupt();
            mThread = null;
        }
        setState(STATE_DISCONNECTED);
    }

    public boolean isStreaming() {
        return STATE_STREAMING.equals(mState);
    }

    public String getState() {
        return mState;
    }

    private void setState(final String newState) {
        if (newState.equals(mState)) {
            return;
        }
        mState = newState;
        if (mListener != null) {
            mListener.onStateChanged(newState);
        }
    }

    private void runLoop() {
        MjpegParser parser = new MjpegParser();
        boolean first = true;
        byte[] chunk = new byte[4096];

        while (!mStop) {
            setState(first ? STATE_CONNECTING : STATE_RECONNECTING);
            first = false;
            parser.reset();

            HttpURLConnection conn = null;
            InputStream in = null;

            try {
                URL url = new URL(mStreamUrl);
                conn = (HttpURLConnection) url.openConnection();
                mActiveConn = conn;
                conn.setConnectTimeout(mTimeoutMs);
                conn.setReadTimeout(mTimeoutMs);
                conn.setRequestMethod("GET");
                conn.setUseCaches(false);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP error code: " + responseCode);
                }

                in = conn.getInputStream();
                mActiveIn = in;
                setState(STATE_STREAMING);

                long winStart = SystemClock.elapsedRealtime();
                int frameCount = 0;
                float currentFps = 0.0f;

                while (!mStop) {
                    int bytesRead = in.read(chunk);
                    if (bytesRead <= 0) {
                        break;
                    }

                    List<byte[]> jpegs = parser.feed(chunk, 0, bytesRead);
                    for (byte[] jpegData : jpegs) {
                        Bitmap bitmap = null;
                        List<ColorDetector.Detection> detections = null;

                        if (mDetectionEnabled) {
                            try {
                                MatOfByte matOfByte = new MatOfByte(jpegData);
                                Mat matBgr = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);
                                matOfByte.release();
                                if (matBgr != null && !matBgr.empty()) {
                                    detections = mDetector.detect(matBgr);
                                    mDetector.draw(matBgr, detections);
                                    Mat matRgba = new Mat();
                                    Imgproc.cvtColor(matBgr, matRgba, Imgproc.COLOR_BGR2RGBA);
                                    bitmap = Bitmap.createBitmap(matRgba.cols(), matRgba.rows(), Bitmap.Config.ARGB_8888);
                                    Utils.matToBitmap(matRgba, bitmap);
                                    matBgr.release();
                                    matRgba.release();
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "Lỗi xử lý OpenCV: " + t.getMessage(), t);
                            }
                        }

                        if (bitmap == null) {
                            bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
                        }

                        if (bitmap != null) {
                            frameCount++;
                            long now = SystemClock.elapsedRealtime();
                            long elapsed = now - winStart;
                            if (elapsed >= 1000) {
                                currentFps = (frameCount * 1000.0f) / elapsed;
                                winStart = now;
                                frameCount = 0;
                            }

                            if (mListener != null && !mStop) {
                                mListener.onFrame(bitmap, currentFps);
                                if (detections != null) {
                                    mListener.onDetections(detections);
                                }
                            }
                        }
                    }
                }

            } catch (Exception e) {
                if (!mStop) {
                    Log.w(TAG, "Stream error: " + e.getMessage());
                    if (mListener != null) {
                        mListener.onError(e.getMessage() != null ? e.getMessage() : "Mất kết nối camera");
                    }
                }
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Exception ignored) {}
                }
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }

            if (mStop) {
                break;
            }

            setState(STATE_RECONNECTING);
            try {
                Thread.sleep(mRetryMs);
            } catch (InterruptedException e) {
                break;
            }
        }

        setState(STATE_DISCONNECTED);
    }
}
