package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

/**
 * Two-line app title: "Nandagram" with "Goloka Messenger" at one third the size.
 * Drawn in white so a MULTIPLY color filter can tint it like the old wordmark.
 */
public class NandagramWordmarkDrawable extends Drawable {

    public static final String TITLE = "Nandagram";
    public static final String SUBTITLE = "Goloka Messenger";

    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float titleSize;
    private final int width;
    private final int height;
    private final float gap;

    public NandagramWordmarkDrawable(float titleSizePx) {
        titleSize = titleSizePx;
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(titleSizePx);
        titlePaint.setTypeface(AndroidUtilities.bold());
        subtitlePaint.setColor(Color.WHITE);
        subtitlePaint.setTextSize(titleSizePx / 3f);
        subtitlePaint.setTypeface(AndroidUtilities.bold());

        float titleWidth = titlePaint.measureText(TITLE);
        float subtitleWidth = subtitlePaint.measureText(SUBTITLE);
        width = (int) Math.ceil(Math.max(titleWidth, subtitleWidth));
        gap = titleSizePx * 0.08f;
        Paint.FontMetrics titleMetrics = titlePaint.getFontMetrics();
        Paint.FontMetrics subtitleMetrics = subtitlePaint.getFontMetrics();
        height = (int) Math.ceil(
            (titleMetrics.descent - titleMetrics.ascent)
                + gap
                + (subtitleMetrics.descent - subtitleMetrics.ascent)
        );
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        float titleWidth = titlePaint.measureText(TITLE);
        float subtitleWidth = subtitlePaint.measureText(SUBTITLE);
        Paint.FontMetrics titleMetrics = titlePaint.getFontMetrics();
        Paint.FontMetrics subtitleMetrics = subtitlePaint.getFontMetrics();
        float titleX = bounds.left + (bounds.width() - titleWidth) / 2f;
        float titleY = bounds.top - titleMetrics.ascent;
        canvas.drawText(TITLE, titleX, titleY, titlePaint);
        float subtitleX = bounds.left + (bounds.width() - subtitleWidth) / 2f;
        float subtitleY = titleY + titleMetrics.descent + gap - subtitleMetrics.ascent;
        canvas.drawText(SUBTITLE, subtitleX, subtitleY, subtitlePaint);
    }

    @Override
    public int getIntrinsicWidth() {
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }

    @Override
    public void setAlpha(int alpha) {
        titlePaint.setAlpha(alpha);
        subtitlePaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        titlePaint.setColorFilter(colorFilter);
        subtitlePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static class TextPaint extends android.text.TextPaint {
        TextPaint(int flags) {
            super(flags);
        }
    }
}
