package se.bitcraze.crazyfliecontrol2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Circular Knob (Radial Joystick) for Roll and Pitch control.
 * Matches the concentric circle design from user specification:
 * - Top: -pitch (Forward)
 * - Bottom: +pitch (Backward)
 * - Left: +roll (Left)
 * - Right: -roll (Right)
 */
public class CircularKnobView extends View {

    public interface OnKnobMoveListener {
        void onMoved(float roll, float pitch);
    }

    private Paint mRingPaint;
    private Paint mCenterCrossPaint;
    private Paint mKnobPaint;
    private Paint mKnobShadowPaint;
    private Paint mTextPaint;
    private Paint mSubTextPaint;

    private float mCenterX;
    private float mCenterY;
    private float mOuterRadius;
    private float mInnerRadius;
    private float mKnobRadius;

    private float mKnobX;
    private float mKnobY;
    private boolean mIsDragging = false;

    private OnKnobMoveListener mListener;

    public CircularKnobView(Context context) {
        super(context);
        init();
    }

    public CircularKnobView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CircularKnobView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRingPaint.setColor(Color.parseColor("#4A7A96"));
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(2.5f);

        mCenterCrossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mCenterCrossPaint.setColor(Color.parseColor("#80A0B5"));
        mCenterCrossPaint.setStyle(Paint.Style.STROKE);
        mCenterCrossPaint.setStrokeWidth(1.5f);

        mKnobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mKnobPaint.setColor(Color.parseColor("#1B4965"));
        mKnobPaint.setStyle(Paint.Style.FILL);

        mKnobShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mKnobShadowPaint.setColor(Color.parseColor("#20000000"));
        mKnobShadowPaint.setStyle(Paint.Style.FILL);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setColor(Color.parseColor("#1B4965"));
        mTextPaint.setTextSize(20f);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setFakeBoldText(true);

        mSubTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mSubTextPaint.setColor(Color.parseColor("#666666"));
        mSubTextPaint.setTextSize(16f);
        mSubTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnKnobMoveListener(OnKnobMoveListener listener) {
        mListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mCenterX = w / 2f;
        mCenterY = h / 2f;

        // Leave ample margin for label texts around the perimeter
        float availableRadius = Math.min(w, h) / 2f - 60f;
        if (availableRadius < 25f) {
            availableRadius = 25f;
        }
        mOuterRadius = availableRadius;
        mInnerRadius = mOuterRadius * 0.55f;
        mKnobRadius = mOuterRadius * 0.28f;

        mKnobX = mCenterX;
        mKnobY = mCenterY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Crosshair lines (faint)
        canvas.drawLine(mCenterX - mOuterRadius - 6, mCenterY, mCenterX + mOuterRadius + 6, mCenterY, mCenterCrossPaint);
        canvas.drawLine(mCenterX, mCenterY - mOuterRadius - 6, mCenterX, mCenterY + mOuterRadius + 6, mCenterCrossPaint);

        // 2. Concentric circles
        canvas.drawCircle(mCenterX, mCenterY, mOuterRadius, mRingPaint);
        canvas.drawCircle(mCenterX, mCenterY, mInnerRadius, mRingPaint);
        canvas.drawCircle(mCenterX, mCenterY, mOuterRadius * 0.25f, mCenterCrossPaint);

        // 3. Direction labels matching uav_udp_console and user sketch exactly:
        // Top: -roll (Forward/Tiến)
        canvas.drawText("-roll", mCenterX, mCenterY - mOuterRadius - 10, mTextPaint);

        // Bottom: +roll (Backward/Lùi)
        canvas.drawText("+roll", mCenterX, mCenterY + mOuterRadius + 24, mTextPaint);

        // Left: +pitch (Left/Trái)
        mTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("+pitch", mCenterX - mOuterRadius - 8, mCenterY + 7, mTextPaint);

        // Right: -pitch (Right/Phải)
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("-pitch", mCenterX + mOuterRadius + 8, mCenterY + 7, mTextPaint);

        // Reset text align
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        // 4. Center Draggable Knob
        canvas.drawCircle(mKnobX + 2, mKnobY + 3, mKnobRadius, mKnobShadowPaint);
        canvas.drawCircle(mKnobX, mKnobY, mKnobRadius, mKnobPaint);
        canvas.drawCircle(mKnobX, mKnobY, mKnobRadius * 0.45f, mRingPaint);
    }

    private static final float MAX_ANGLE_DEG = 4.0f;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                mIsDragging = true;
                float dx = event.getX() - mCenterX;
                float dy = event.getY() - mCenterY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float maxTravel = mOuterRadius;

                if (dist > maxTravel) {
                    dx = (dx / dist) * maxTravel;
                    dy = (dy / dist) * maxTravel;
                }
                mKnobX = mCenterX + dx;
                mKnobY = mCenterY + dy;

                float normX = dx / maxTravel; // -1.0 (trái) .. +1.0 (phải)
                float normY = dy / maxTravel; // -1.0 (trên) .. +1.0 (dưới)

                // Trục dọc: kéo lên -> -roll (tiến), kéo xuống -> +roll (lùi). Max là 4.0
                float roll = normY * MAX_ANGLE_DEG;

                // Trục ngang: kéo trái -> +pitch (trái), kéo phải -> -pitch (phải). Max là 4.0
                float pitch = -normX * MAX_ANGLE_DEG;

                if (mListener != null) {
                    mListener.onMoved(roll, pitch);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsDragging = false;
                mKnobX = mCenterX;
                mKnobY = mCenterY;
                if (mListener != null) {
                    mListener.onMoved(0.0f, 0.0f);
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
