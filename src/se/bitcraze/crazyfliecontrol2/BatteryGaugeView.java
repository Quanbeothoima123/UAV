package se.bitcraze.crazyfliecontrol2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Large horizontal Battery Gauge with 4 segments and voltage label.
 * Matches the user interface mockup:
 * [====] -battery- voltage
 */
public class BatteryGaugeView extends View {

    private Paint mOutlinePaint;
    private Paint mSegmentPaint;
    private Paint mTextPaint;

    private float mVoltage = -1.0f; // in Volts (e.g. 3.82)
    private int mPercentage = 0;

    private final RectF mBodyRect = new RectF();
    private final RectF mTerminalRect = new RectF();
    private final RectF mSegmentRect = new RectF();

    public BatteryGaugeView(Context context) {
        super(context);
        init();
    }

    public BatteryGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BatteryGaugeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mOutlinePaint.setColor(Color.parseColor("#2C5E78"));
        mOutlinePaint.setStyle(Paint.Style.STROKE);
        mOutlinePaint.setStrokeWidth(3.5f);

        mSegmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mSegmentPaint.setColor(Color.parseColor("#2C5E78"));
        mSegmentPaint.setStyle(Paint.Style.FILL);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setColor(Color.parseColor("#1B4965"));
        mTextPaint.setTextSize(22f);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setFakeBoldText(true);
    }

    public void setBattery(float voltage, int percentage) {
        mVoltage = voltage;
        mPercentage = percentage;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Reserve space for text at the bottom
        float textMarginBottom = 28f;
        float battHeight = h - textMarginBottom - 8f;
        float battWidth = Math.min(w * 0.85f, battHeight * 2.8f);
        float battLeft = (w - battWidth) / 2f - 6f;
        float battTop = 6f;

        float terminalWidth = battWidth * 0.04f;
        float terminalHeight = battHeight * 0.38f;
        float terminalLeft = battLeft + battWidth;
        float terminalTop = battTop + (battHeight - terminalHeight) / 2f;

        // Choose color based on voltage/percentage
        int battColor;
        if (mVoltage > 0f) {
            if (mVoltage < 3.45f) {
                battColor = Color.parseColor("#D32F2F"); // Red alert
            } else if (mVoltage < 3.65f) {
                battColor = Color.parseColor("#E65100"); // Orange
            } else {
                battColor = Color.parseColor("#2C5E78"); // Healthy slate
            }
        } else {
            battColor = Color.parseColor("#808080"); // Unknown gray
        }

        mOutlinePaint.setColor(battColor);
        mSegmentPaint.setColor(battColor);
        mTextPaint.setColor(battColor);

        // 1. Draw outer body rounded rect
        mBodyRect.set(battLeft, battTop, battLeft + battWidth, battTop + battHeight);
        canvas.drawRoundRect(mBodyRect, 10f, 10f, mOutlinePaint);

        // 2. Draw right terminal positive nipple
        mTerminalRect.set(terminalLeft, terminalTop, terminalLeft + terminalWidth, terminalTop + terminalHeight);
        canvas.drawRoundRect(mTerminalRect, 3f, 3f, mOutlinePaint);

        // 3. Draw 4 segments inside
        int activeSegments;
        if (mVoltage <= 0f) {
            activeSegments = 0;
        } else if (mVoltage >= 3.95f) {
            activeSegments = 4;
        } else if (mVoltage >= 3.80f) {
            activeSegments = 3;
        } else if (mVoltage >= 3.65f) {
            activeSegments = 2;
        } else if (mVoltage >= 3.45f) {
            activeSegments = 1;
        } else {
            activeSegments = 0;
        }

        float innerPadding = 6f;
        float innerWidth = battWidth - innerPadding * 2;
        float innerHeight = battHeight - innerPadding * 2;
        float gap = 5f;
        float segWidth = (innerWidth - gap * 3) / 4f;

        for (int i = 0; i < 4; i++) {
            if (i < activeSegments) {
                float segLeft = battLeft + innerPadding + i * (segWidth + gap);
                float segTop = battTop + innerPadding;
                mSegmentRect.set(segLeft, segTop, segLeft + segWidth, segTop + innerHeight);
                canvas.drawRoundRect(mSegmentRect, 4f, 4f, mSegmentPaint);
            }
        }

        // 4. Draw text underneath
        String label;
        if (mVoltage > 0f) {
            label = String.format(java.util.Locale.US, "Pin: %.2f V (%d%%)", mVoltage, mPercentage);
        } else {
            label = "-battery- voltage (? V)";
        }
        canvas.drawText(label, w / 2f, h - 6f, mTextPaint);
    }
}
