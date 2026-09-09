package se.bitcraze.crazyfliecontrol2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * View hiển thị stream video từ Camera ESP bằng TextureView (tăng tốc phần cứng).
 * Tự động căn chỉnh tỷ lệ hình ảnh (Fit Center), hỗ trợ viền HUD hiện đại và hiển thị FPS.
 */
public class CameraStreamView extends TextureView implements TextureView.SurfaceTextureListener {

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Matrix mDrawMatrix = new Matrix();
    private final RectF mSrcRect = new RectF();
    private final RectF mDstRect = new RectF();

    private volatile Bitmap mCurrentBitmap;
    private volatile float mFps = 0.0f;
    private volatile String mStatusText = "CAM SẴN SÀNG";
    private volatile boolean mIsStreaming = false;
    private boolean mSurfaceAvailable = false;

    public CameraStreamView(Context context) {
        super(context);
        init();
    }

    public CameraStreamView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CameraStreamView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSurfaceTextureListener(this);
        setOpaque(false);

        mBgPaint.setColor(Color.parseColor("#141920"));
        mBgPaint.setStyle(Paint.Style.FILL);

        mBorderPaint.setColor(Color.parseColor("#37474F"));
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(2.5f);

        mHudPaint.setColor(Color.parseColor("#00E5FF"));
        mHudPaint.setStyle(Paint.Style.STROKE);
        mHudPaint.setStrokeWidth(2.0f);

        mTextPaint.setColor(Color.parseColor("#B0BEC5"));
        mTextPaint.setTextSize(22.0f);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void updateFrame(final Bitmap bitmap, final float fps) {
        mCurrentBitmap = bitmap;
        mFps = fps;
        mIsStreaming = true;
        render();
    }

    public void setStatus(final String status, final boolean isStreaming) {
        mStatusText = status;
        mIsStreaming = isStreaming;
        if (!isStreaming) {
            mCurrentBitmap = null;
        }
        render();
    }

    public synchronized void render() {
        if (!mSurfaceAvailable || !isAvailable()) {
            return;
        }

        Canvas canvas = null;
        try {
            canvas = lockCanvas();
            if (canvas == null) {
                return;
            }

            int w = getWidth();
            int h = getHeight();

            // 1. Vẽ nền
            canvas.drawColor(Color.TRANSPARENT);
            canvas.drawRect(0, 0, w, h, mBgPaint);

            Bitmap bmp = mCurrentBitmap;
            if (bmp != null && !bmp.isRecycled() && mIsStreaming) {
                // 2. Tính toán Matrix căn giữa (Fit Center)
                mSrcRect.set(0, 0, bmp.getWidth(), bmp.getHeight());
                mDstRect.set(0, 0, w, h);
                mDrawMatrix.setRectToRect(mSrcRect, mDstRect, Matrix.ScaleToFit.CENTER);

                canvas.drawBitmap(bmp, mDrawMatrix, mBitmapPaint);

                // 3. Vẽ góc ngắm HUD (Viewfinder brackets)
                drawViewfinderCorners(canvas, w, h);

                // 4. Vẽ FPS badge góc trên bên trái
                mTextPaint.setTextSize(18.0f);
                mTextPaint.setColor(Color.parseColor("#00E676")); // Xanh lá
                mTextPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(String.format("LIVE • %.1f FPS", mFps), 12, 24, mTextPaint);
            } else {
                // Vẽ trạng thái chờ kết nối
                drawIdleView(canvas, w, h);
            }

            // 5. Vẽ đường viền khung hình
            canvas.drawRect(1, 1, w - 1, h - 1, mBorderPaint);

        } catch (Exception ignored) {
        } finally {
            if (canvas != null) {
                try {
                    unlockCanvasAndPost(canvas);
                } catch (Exception ignored) {}
            }
        }
    }

    private void drawViewfinderCorners(Canvas canvas, int w, int h) {
        float len = 16.0f;
        float pad = 10.0f;

        // Top-left
        canvas.drawLine(pad, pad, pad + len, pad, mHudPaint);
        canvas.drawLine(pad, pad, pad, pad + len, mHudPaint);

        // Top-right
        canvas.drawLine(w - pad, pad, w - pad - len, pad, mHudPaint);
        canvas.drawLine(w - pad, pad, w - pad, pad + len, mHudPaint);

        // Bottom-left
        canvas.drawLine(pad, h - pad, pad + len, h - pad, mHudPaint);
        canvas.drawLine(pad, h - pad, pad, h - pad - len, mHudPaint);

        // Bottom-right
        canvas.drawLine(w - pad, h - pad, w - pad - len, h - pad, mHudPaint);
        canvas.drawLine(w - pad, h - pad, w - pad, h - pad - len, mHudPaint);

        // Crosshair tâm
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        canvas.drawLine(cx - 8, cy, cx + 8, cy, mHudPaint);
        canvas.drawLine(cx, cy - 8, cx, cy + 8, mHudPaint);
    }

    private void drawIdleView(Canvas canvas, int w, int h) {
        // Vẽ icon camera đơn giản hoặc tâm ngắm mờ
        float cx = w / 2.0f;
        float cy = h / 2.0f;

        mHudPaint.setColor(Color.parseColor("#37474F"));
        canvas.drawCircle(cx, cy - 12, 28, mHudPaint);
        canvas.drawCircle(cx, cy - 12, 10, mHudPaint);

        mTextPaint.setTextSize(20.0f);
        mTextPaint.setColor(Color.parseColor("#90A4AE"));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(mStatusText, cx, cy + 40, mTextPaint);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mSurfaceAvailable = true;
        render();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        render();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mSurfaceAvailable = false;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
