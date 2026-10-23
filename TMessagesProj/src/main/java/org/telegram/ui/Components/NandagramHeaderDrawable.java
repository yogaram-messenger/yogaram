package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.R;

/**
 * Main chat-list header mark: round Jagannatha icon + two-line Nandagram wordmark.
 */
public class NandagramHeaderDrawable extends Drawable {

    private final Drawable jagannatha;
    private final NandagramWordmarkDrawable wordmark;
    private final int gapPx;
    private final int jagannathaSize;
    private final int width;
    private final int height;

    public NandagramHeaderDrawable(Context context, float titleSizePx, int jagannathaSizePx) {
        jagannathaSize = jagannathaSizePx;
        gapPx = Math.max(1, Math.round(titleSizePx * 0.35f));
        Drawable src = ContextCompat.getDrawable(context, R.drawable.jagannatha_round);
        jagannatha = src != null ? src.mutate() : null;
        wordmark = new NandagramWordmarkDrawable(titleSizePx);
        width = jagannathaSize + gapPx + wordmark.getIntrinsicWidth();
        height = Math.max(jagannathaSize, wordmark.getIntrinsicHeight());
    }

    public NandagramWordmarkDrawable getWordmark() {
        return wordmark;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int left = bounds.left;
        int top = bounds.top + (bounds.height() - height) / 2;
        if (jagannatha != null) {
            int jTop = top + (height - jagannathaSize) / 2;
            jagannatha.setBounds(left, jTop, left + jagannathaSize, jTop + jagannathaSize);
            jagannatha.draw(canvas);
        }
        int wordLeft = left + jagannathaSize + gapPx;
        int wordTop = top + (height - wordmark.getIntrinsicHeight()) / 2;
        wordmark.setBounds(wordLeft, wordTop, wordLeft + wordmark.getIntrinsicWidth(), wordTop + wordmark.getIntrinsicHeight());
        wordmark.draw(canvas);
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
        if (jagannatha != null) {
            jagannatha.setAlpha(alpha);
        }
        wordmark.setAlpha(alpha);
        invalidateSelf();
    }

    /**
     * Tints only the text wordmark so Jagannatha keeps its natural colors.
     */
    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        wordmark.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
