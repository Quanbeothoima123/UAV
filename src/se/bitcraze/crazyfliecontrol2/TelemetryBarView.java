package se.bitcraze.crazyfliecontrol2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Telemetry Bar / Mini Grid Chart view for Roll, Pitch, and Altm.
 * Matches the 3 top-right telemetry boxes in the user mockup.
 */
public class TelemetryBarView extends View {

    private Paint mBorderPaint;
    private Paint mGridPaint;
    private Paint mBarPaint;
    private Paint mTitlePaint;
    private Paint mValuePaint;

    private String mTitle = "Value";
    private String mUnit = "";
    private float mCurrentValue = 0.0f;
    private float mMinValue = -30.0f;
    private float mMaxValue = 30.0f;

    private static final int HISTORY_COUNT = 7;
    private final float[] mHistory = new float[HISTORY_COUNT];
    private int mHistoryIndex = 0;

    private final RectF mBoxRect = new RectF();
    private final RectF mBarRect = new RectF();

    public TelemetryBarView(Context context) {
        super(context);
        init();
    }

    public TelemetryBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TelemetryBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint.setColor(Color.parseColor("#B0C4DE"));
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(1.8f);

        mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mGridPaint.setColor(Color.parseColor("#E4EBF2"));
        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(1.0f);

        mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBarPaint.setColor(Color.parseColor("#2C5E78"));
        mBarPaint.setStyle(Paint.Style.FILL);

        mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTitlePaint.setColor(Color.parseColor("#1B4965"));
        mTitlePaint.setTextSize(22f);
        mTitlePaint.setTextAlign(Paint.Align.CENTER);
        mTitlePaint.setFakeBoldText(true);

        mValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mValuePaint.setColor(Color.parseColor("#0A5C8A"));
        mValuePaint.setTextSize(18f);
        mValuePaint.setTextAlign(Paint.Align.CENTER);
        mValuePaint.setFakeBoldText(true);
    }

    public void configure(String title, String unit, float minVal, float maxVal) {
        mTitle = title;
        mUnit = unit;
        mMinValue = minVal;
        mMaxValue = maxVal;
        invalidate();
    }

    public void setValue(float value) {
        mCurrentValue = value;
        mHistory[mHistoryIndex] = value;
        mHistoryIndex = (mHistoryIndex + 1) % HISTORY_COUNT;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        mBoxRect.set(1f, 1f, w - 1f, h - 1f);
        canvas.drawRoundRect(mBoxRect, 6f, 6f, mBorderPaint);

        // Header Title
        float titleHeight = 26f;
        canvas.drawLine(1f, titleHeight, w - 1f, titleHeight, mBorderPaint);
        canvas.drawText(mTitle, w / 2f, titleHeight - 6f, mTitlePaint);

        // Chart area
        float chartTop = titleHeight + 4f;
        float chartBottom = h - 22f;
        float chartLeft = 6f;
        float chartRight = w - 6f;
        float chartHeight = chartBottom - chartTop;
        float chartWidth = chartRight - chartLeft;

        // Draw light grid lines
        for (int i = 1; i <= 3; i++) {
            float gy = chartTop + chartHeight * (i / 4f);
            canvas.drawLine(chartLeft, gy, chartRight, gy, mGridPaint);
        }
        for (int i = 1; i <= HISTORY_COUNT - 1; i++) {
            float gx = chartLeft + chartWidth * (i / (float) HISTORY_COUNT);
            canvas.drawLine(gx, chartTop, gx, chartBottom, mGridPaint);
        }

        // Draw history bars
        float barGap = 2.5f;
        float barWidth = (chartWidth - barGap * (HISTORY_COUNT - 1)) / HISTORY_COUNT;
        float range = Math.max(0.01f, mMaxValue - mMinValue);

        for (int i = 0; i < HISTORY_COUNT; i++) {
            int idx = (mHistoryIndex + i) % HISTORY_COUNT;
            float val = mHistory[idx];
            float clamped = Math.max(mMinValue, Math.min(mMaxValue, val));

            float fraction;
            if (mMinValue >= 0) {
                // Unipolar (e.g. altitude 0 to 2m)
                fraction = (clamped - mMinValue) / range;
            } else {
                // Bipolar (e.g. pitch/roll -30 to +30 deg)
                fraction = Math.abs(clamped) / (range / 2f);
            }
            fraction = Math.max(0.05f, Math.min(1.0f, fraction));

            float barH = chartHeight * fraction;
            float bx = chartLeft + i * (barWidth + barGap);
            float by = chartBottom - barH;

            mBarRect.set(bx, by, bx + barWidth, chartBottom);
            canvas.drawRoundRect(mBarRect, 2f, 2f, mBarPaint);
        }

        // Bottom text: current value
        String valueStr;
        if ("m".equals(mUnit)) {
            valueStr = String.format(Locale.US, "%.2f %s", mCurrentValue, mUnit);
        } else {
            valueStr = String.format(Locale.US, "%.1f%s", mCurrentValue, mUnit);
        }
        canvas.drawText(valueStr, w / 2f, h - 5f, mValuePaint);
    }
}
