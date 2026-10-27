package com.dewanteam.textcipher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class OverlayManager {
    public static final class MessageOverlay {
        public final String id;
        public final String payload;
        public final String plaintext;
        public final Rect bounds;

        public MessageOverlay(String id, String payload, String plaintext, Rect bounds) {
            this.id = id;
            this.payload = payload;
            this.plaintext = plaintext;
            this.bounds = new Rect(bounds);
        }
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Map<String, TextView> views = new HashMap<>();
    private final Map<String, Rect> lastBounds = new HashMap<>();
    private TextView encryptChip;
    private Rect chipBounds;
    private View.OnClickListener chipAction;

    public OverlayManager(Context context) {
        this.context = context.getApplicationContext();
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void updateMessages(Map<String, MessageOverlay> wanted) {
        Set<String> stale = new HashSet<>(views.keySet());
        stale.removeAll(wanted.keySet());
        for (String id : stale) removeView(id);

        ThemeManager.Theme theme = ThemeManager.get(context);
        for (MessageOverlay overlay : wanted.values()) {
            TextView view = views.get(overlay.id);
            if (view == null) {
                view = createTextView(theme);
                try {
                    windowManager.addView(view, layoutParams(overlay.bounds));
                    views.put(overlay.id, view);
                    lastBounds.put(overlay.id, new Rect(overlay.bounds));
                } catch (RuntimeException exception) {
                    continue;
                }
            } else {
                Rect previous = lastBounds.get(overlay.id);
                if (previous == null || !previous.equals(overlay.bounds)) {
                    try {
                        windowManager.updateViewLayout(view, layoutParams(overlay.bounds));
                        lastBounds.put(overlay.id, new Rect(overlay.bounds));
                    } catch (RuntimeException exception) {
                        removeView(overlay.id);
                        continue;
                    }
                }
            }
            if (!overlay.plaintext.contentEquals(view.getText())) view.setText(MessageFormatter.format(overlay.plaintext));
        }
    }

    public void refreshTheme() {
        ThemeManager.Theme theme = ThemeManager.get(context);
        for (TextView view : views.values()) {
            view.setTextColor(theme.overlayText);
            view.setBackground(new TooltipDrawable(context, theme));
            view.setPadding(dp(10), dp(7), dp(10), dp(7));
            view.requestLayout();
        }
        if (encryptChip != null) {
            encryptChip.setTextColor(theme.overlayText);
            encryptChip.setBackground(new TooltipDrawable(context, theme));
            encryptChip.requestLayout();
        }
    }

    public void updateEncryptChip(Rect bounds, View.OnClickListener action) {
        if (bounds == null || bounds.isEmpty() || action == null) {
            removeEncryptChip();
            return;
        }

        if (encryptChip == null) {
            ThemeManager.Theme theme = ThemeManager.get(context);
            TextView chip = new TextView(context);
            chip.setText("Encrypt");
            chip.setTextColor(theme.overlayText);
            chip.setTextSize(13f);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setBackground(new TooltipDrawable(context, theme));
            chip.setElevation(dp(5));
            chip.setClickable(true);
            chip.setFocusable(false);
            chip.setOnClickListener(view -> {
                if (chipAction != null) chipAction.onClick(view);
            });
            try {
                windowManager.addView(chip, chipLayout(bounds));
                encryptChip = chip;
            } catch (RuntimeException exception) {
                return;
            }
        } else if (chipBounds == null || !chipBounds.equals(bounds)) {
            try {
                windowManager.updateViewLayout(encryptChip, chipLayout(bounds));
            } catch (RuntimeException exception) {
                removeEncryptChip();
                return;
            }
        }
        chipBounds = new Rect(bounds);
        chipAction = action;
    }

    private WindowManager.LayoutParams chipLayout(Rect bounds) {
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int x = Math.max(dp(8), Math.min(bounds.right - dp(96), screenWidth - dp(96)));
        int y = Math.max(dp(8), bounds.top - dp(40));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = x;
        params.y = y;
        return params;
    }

    private void removeEncryptChip() {
        if (encryptChip != null) {
            try {
                windowManager.removeView(encryptChip);
            } catch (RuntimeException ignored) {
            }
        }
        encryptChip = null;
        chipBounds = null;
        chipAction = null;
    }

    public void clearMessageOverlays() {
        for (String id : new HashSet<>(views.keySet())) removeView(id);
    }

    public void clear() {
        clearMessageOverlays();
        removeEncryptChip();
    }

    private TextView createTextView(ThemeManager.Theme theme) {
        TextView view = new TextView(context);
        view.setTextColor(theme.overlayText);
        view.setTextSize(14f);
        view.setLineSpacing(dp(2), 1.05f);
        view.setPadding(dp(10), dp(7), dp(10), dp(7));
        view.setBackground(new TooltipDrawable(context, theme));
        view.setElevation(dp(4));
        view.setMaxLines(6);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        return view;
    }

    private WindowManager.LayoutParams layoutParams(Rect bounds) {
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        int width = Math.max(dp(80), Math.min(Math.max(1, bounds.width()), screenWidth - dp(16)));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = Math.max(0, Math.min(bounds.left, screenWidth - dp(16)));
        params.y = Math.max(0, Math.min(bounds.top, screenHeight - dp(24)));
        return params;
    }

    private void removeView(String id) {
        TextView view = views.remove(id);
        lastBounds.remove(id);
        if (view == null) return;
        try {
            windowManager.removeView(view);
        } catch (RuntimeException ignored) {
        }
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
    //         ThemeManager.Theme theme = ThemeManager.get(this);
    private final class TooltipDrawable extends Drawable {
        private final ThemeManager.Theme theme;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF drawingBounds = new RectF();
        private final Bitmap backgroundTexture;
        private final Bitmap frameTexture;
        private final Bitmap legacyTexture;

        TooltipDrawable(Context context, ThemeManager.Theme theme) {
            this.theme = theme;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(theme.overlayBackground);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(1));
            border.setColor(theme.overlayBorder);

            File backgroundFile = ThemeManager.backgroundFile(context);
            File frameFile = ThemeManager.frameFile(context);
            backgroundTexture = backgroundFile == null ? null : BitmapFactory.decodeFile(backgroundFile.getAbsolutePath());
            frameTexture = frameFile == null ? null : BitmapFactory.decodeFile(frameFile.getAbsolutePath());

            File oldTexture = ThemeManager.textureFile(context);
            legacyTexture = oldTexture == null || backgroundTexture != null
                    ? null : BitmapFactory.decodeFile(oldTexture.getAbsolutePath());
            if (legacyTexture != null) fill.setShader(new BitmapShader(legacyTexture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            drawingBounds.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
            float radius = dp(Math.round(theme.overlayRadiusDp));

            if (backgroundTexture != null) drawNineSlice(canvas, backgroundTexture, bounds, ThemeManager.backgroundBorder(context));
            else canvas.drawRoundRect(drawingBounds, radius, radius, fill);

            if (frameTexture != null) drawNineSlice(canvas, frameTexture, bounds, ThemeManager.frameBorder(context));
            else canvas.drawRoundRect(drawingBounds, radius, radius, border);
        }

        private void drawNineSlice(Canvas canvas, Bitmap bitmap, Rect target, int requestedBorder) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int sliceX = Math.min(requestedBorder, Math.min(width / 2, height / 2));
            int sliceY = sliceX;
            float edgeX = Math.min(dp(sliceX), Math.max(1, target.width() / 2f));
            float edgeY = Math.min(dp(sliceY), Math.max(1, target.height() / 2f));

            int[] srcX = {0, sliceX, width - sliceX, width};
            int[] srcY = {0, sliceY, height - sliceY, height};
            float[] dstX = {target.left, target.left + edgeX, target.right - edgeX, target.right};
            float[] dstY = {target.top, target.top + edgeY, target.bottom - edgeY, target.bottom};
            Rect source = new Rect();
            RectF destination = new RectF();

            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    if (srcX[column + 1] <= srcX[column] || srcY[row + 1] <= srcY[row]
                            || dstX[column + 1] <= dstX[column] || dstY[row + 1] <= dstY[row]) {
                        continue;
                    }
                    source.set(srcX[column], srcY[row], srcX[column + 1], srcY[row + 1]);
                    destination.set(dstX[column], dstY[row], dstX[column + 1], dstY[row + 1]);
                    canvas.drawBitmap(bitmap, source, destination, fill);
                }
            }
        }

        @Override public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            border.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            fill.setColorFilter(filter);
            border.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
