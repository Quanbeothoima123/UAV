package se.bitcraze.crazyfliecontrol2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import java.util.Locale;

/**
 * Telemetry Card view for Roll, Altm, and Pitch.
 * Displays title header on top and large numeric value in the center.
 * Waveform / grid has been removed as requested.
 */
public class TelemetryBarView extends View {

    private Paint mBgPaint;
    private Paint mBorderPaint;
    private Paint mTitlePaint;
    private Paint mValuePaint;

    private String mTitle = "Value";
    private String mUnit = "";
    private float mCurrentValue = 0.0f;
    private float mDensity = 1.0f;

    private final RectF mBoxRect = new RectF();

    public TelemetryBarView(Context context) {
        super(context);
        init(context);
    }

    public TelemetryBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TelemetryBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        mDensity = dm.density;

        mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBgPaint.setColor(Color.parseColor("#F5F9FD"));
        mBgPaint.setStyle(Paint.Style.FILL);

        mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint.setColor(Color.parseColor("#B0C8DE"));
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(1.6f * mDensity);

        mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTitlePaint.setColor(Color.parseColor("#1B4965"));
        mTitlePaint.setTextSize(12f * mDensity);
        mTitlePaint.setTextAlign(Paint.Align.CENTER);
        mTitlePaint.setFakeBoldText(true);

        mValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mValuePaint.setColor(Color.parseColor("#0A5C8A"));
        mValuePaint.setTextSize(19f * mDensity);
        mValuePaint.setTextAlign(Paint.Align.CENTER);
        mValuePaint.setFakeBoldText(true);
    }

    public void configure(String title, String unit, float minVal, float maxVal) {
        mTitle = title;
        mUnit = unit;
        invalidate();
    }

    public void setValue(float value) {
        mCurrentValue = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float stroke = 1.6f * mDensity;
        float corner = 6f * mDensity;
        mBoxRect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f);

        // Card background & rounded border
        canvas.drawRoundRect(mBoxRect, corner, corner, mBgPaint);
        canvas.drawRoundRect(mBoxRect, corner, corner, mBorderPaint);

        // Header Title
        float titleHeight = 20f * mDensity;
        canvas.drawLine(mBoxRect.left, titleHeight, mBoxRect.right, titleHeight, mBorderPaint);
        float titleBaseline = titleHeight - 5f * mDensity;
        canvas.drawText(mTitle, w / 2f, titleBaseline, mTitlePaint);

        // Big numeric value in the exact center of the remaining card space
        String valueStr;
        if ("m".equals(mUnit)) {
            valueStr = String.format(Locale.US, "%.2f m", mCurrentValue);
        } else {
            valueStr = String.format(Locale.US, "%.1f%s", mCurrentValue, mUnit);
        }

        float contentCenterY = titleHeight + (h - titleHeight) / 2f;
        float textY = contentCenterY - (mValuePaint.descent() + mValuePaint.ascent()) / 2f;
        canvas.drawText(valueStr, w / 2f, textY, mValuePaint);
    }
}
