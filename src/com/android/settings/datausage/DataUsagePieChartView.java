/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.settings.datausage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.settings.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pie chart used by data usage pages to show selected range distribution. */
public class DataUsagePieChartView extends View {

    private static final int[] SLICE_COLORS = {
            Color.rgb(25, 103, 210),
            Color.rgb(15, 157, 88),
            Color.rgb(251, 188, 4),
            Color.rgb(234, 67, 53),
            Color.rgb(155, 81, 224),
            Color.rgb(0, 172, 193),
            Color.rgb(255, 112, 67),
            Color.rgb(92, 107, 192),
    };

    private final Paint mSlicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHolePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCenterTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mPieBounds = new RectF();

    private List<Slice> mSlices = Collections.emptyList();
    private CharSequence mCenterText = "";
    private CharSequence mSubtitle = "";

    public DataUsagePieChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int textColor = Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary);
        int secondaryTextColor =
                Utils.getColorAttrDefaultColor(context, android.R.attr.textColorSecondary);
        int trackColor = Utils.getColorAttrDefaultColor(context, android.R.attr.colorControlNormal);
        int backgroundColor = Utils.getColorAttrDefaultColor(context, android.R.attr.windowBackground);

        mTrackPaint.setStyle(Paint.Style.FILL);
        mTrackPaint.setColor(trackColor);
        mTrackPaint.setAlpha(48);
        mHolePaint.setStyle(Paint.Style.FILL);
        mHolePaint.setColor(backgroundColor);
        mCenterTextPaint.setColor(textColor);
        mCenterTextPaint.setTextAlign(Paint.Align.CENTER);
        mCenterTextPaint.setFakeBoldText(true);
        mCenterTextPaint.setTextSize(sp(22));
        mSubtitlePaint.setColor(secondaryTextColor);
        mSubtitlePaint.setTextAlign(Paint.Align.CENTER);
        mSubtitlePaint.setTextSize(sp(12));
    }

    public void setSlices(@Nullable List<Slice> slices) {
        mSlices = slices == null ? Collections.emptyList() : new ArrayList<>(slices);
        invalidate();
    }

    public void setCenterText(@Nullable CharSequence centerText) {
        mCenterText = centerText == null ? "" : centerText;
        invalidate();
    }

    public void setSubtitle(@Nullable CharSequence subtitle) {
        mSubtitle = subtitle == null ? "" : subtitle;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth() - getPaddingStart() - getPaddingEnd();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }

        float diameter = Math.min(width, height) - dp(8);
        float left = getPaddingStart() + (width - diameter) / 2f;
        float top = getPaddingTop() + (height - diameter) / 2f;
        mPieBounds.set(left, top, left + diameter, top + diameter);

        long total = getTotalUsage();
        if (total <= 0L) {
            canvas.drawOval(mPieBounds, mTrackPaint);
        } else {
            float startAngle = -90f;
            for (int i = 0; i < mSlices.size(); i++) {
                Slice slice = mSlices.get(i);
                if (slice.value <= 0L) {
                    continue;
                }
                float sweepAngle = (slice.value * 360f) / total;
                mSlicePaint.setColor(SLICE_COLORS[i % SLICE_COLORS.length]);
                canvas.drawArc(mPieBounds, startAngle, sweepAngle, true, mSlicePaint);
                startAngle += sweepAngle;
            }
        }

        float centerX = mPieBounds.centerX();
        float centerY = mPieBounds.centerY();
        canvas.drawCircle(centerX, centerY, diameter * 0.28f, mHolePaint);
        drawCenteredText(canvas, mCenterText, centerY - dp(2), mCenterTextPaint, diameter * 0.52f);
        if (!TextUtils.isEmpty(mSubtitle)) {
            drawCenteredText(canvas, mSubtitle, centerY + dp(18), mSubtitlePaint, diameter * 0.56f);
        }
    }

    private long getTotalUsage() {
        long total = 0L;
        for (Slice slice : mSlices) {
            total += Math.max(0L, slice.value);
        }
        return total;
    }

    private void drawCenteredText(
            Canvas canvas, CharSequence text, float baseline, Paint paint, float maxWidth) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        String value = text.toString();
        float originalSize = paint.getTextSize();
        float minSize = sp(10);
        while (paint.measureText(value) > maxWidth && paint.getTextSize() > minSize) {
            paint.setTextSize(paint.getTextSize() - 1f);
        }
        canvas.drawText(value, mPieBounds.centerX(), baseline, paint);
        paint.setTextSize(originalSize);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    public static final class Slice {
        public final CharSequence label;
        public final long value;

        public Slice(CharSequence label, long value) {
            this.label = label;
            this.value = Math.max(0L, value);
        }
    }
}
