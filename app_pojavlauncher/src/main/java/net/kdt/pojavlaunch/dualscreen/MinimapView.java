package net.kdt.pojavlaunch.dualscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Live minimap for the AYN Thor bottom screen. The companion Fabric mod writes a player-centered
 * 128x128 {@code map.png} (north-up) plus {@code map.json} metadata.
 *
 * <p>Minecraft yaw: 0 = south (+Z), increases clockwise (90 = west, 180 = north, 270 = east).
 * The arrow is drawn pointing north (up) and the canvas is rotated by {@code (yaw + 180)} degrees
 * so yaw 0 faces south on the map.
 *
 * <p>Pixel art: {@code inScaled=false} + {@code Paint.setFilterBitmap(false)} (nearest neighbor).
 */
public class MinimapView extends View {
    /** Recenter the pan offset if the user has not dragged for this long when a new seq arrives. */
    private static final long RECENTER_AFTER_MS = 3000L;

    private final Paint mapPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint arrowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayBg = new Paint();
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptySubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final Path arrow = new Path();
    private final Matrix arrowMatrix = new Matrix();

    private Bitmap mapBmp;
    private MapMeta meta;
    private String emptyTitle = "Walk around to generate the map";
    private String emptySub = "Install thor-deck-mod";

    private float panX, panY;
    private float lastTouchX, lastTouchY;
    private boolean panning;
    private long lastPanAt;
    private long appliedSeq = -1;

    public static final class MapMeta {
        public long seq;
        public double x, y, z;
        public float yaw;
        public String dim;
        public String biome;
        public int w, h;
        public int scale;
    }

    public MinimapView(Context c) { super(c); init(); }
    public MinimapView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        mapPaint.setFilterBitmap(false);
        mapPaint.setAntiAlias(false);
        mapPaint.setDither(false);

        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        arrowFill.setColor(Color.WHITE);
        arrowFill.setStyle(Paint.Style.FILL);
        arrowStroke.setColor(0xFF1A1A1A);
        arrowStroke.setStyle(Paint.Style.STROKE);
        arrowStroke.setStrokeWidth(3f);
        arrowStroke.setStrokeJoin(Paint.Join.ROUND);

        overlayBg.setColor(0xAA1A1A1A);
        overlayPaint.setColor(0xFFEAEAEA);
        overlayPaint.setFakeBoldText(true);

        emptyPaint.setColor(0xFFDDDDDD);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        emptySubPaint.setColor(0x99CCCCCC);
        emptySubPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * Swap in a newly decoded map bitmap (already {@code inScaled=false}). Recycles the previous
     * bitmap. Pass {@code null} to show the empty state.
     */
    public void setMapBitmap(Bitmap bmp) {
        Bitmap old = this.mapBmp;
        this.mapBmp = bmp;
        if (old != null && old != bmp && !old.isRecycled()) {
            old.recycle();
        }
        invalidate();
    }

    public void setEmptyCopy(String title, String sub) {
        if (title != null) emptyTitle = title;
        if (sub != null) emptySub = sub;
        invalidate();
    }

    public void setMeta(MapMeta meta) {
        long newSeq = meta == null ? -1 : meta.seq;
        long now = android.os.SystemClock.uptimeMillis();
        if (newSeq != appliedSeq && newSeq >= 0) {
            if (lastPanAt == 0 || (now - lastPanAt) >= RECENTER_AFTER_MS) {
                panX = 0;
                panY = 0;
            }
            appliedSeq = newSeq;
        }
        this.meta = meta;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mapBmp != null && !mapBmp.isRecycled()) {
            mapBmp.recycle();
            mapBmp = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(0xFF1E1E1E);

        if (mapBmp == null || mapBmp.isRecycled()) {
            emptyPaint.setTextSize(dp(16));
            emptySubPaint.setTextSize(dp(13));
            canvas.drawText(emptyTitle, w / 2f, h / 2f - dp(6), emptyPaint);
            canvas.drawText(emptySub, w / 2f, h / 2f + dp(16), emptySubPaint);
            return;
        }

        int bw = mapBmp.getWidth();
        int bh = mapBmp.getHeight();
        srcRect.set(0, 0, bw, bh);

        // Stretch the square map to fill the content area (center-crop if the view is not square).
        float scale = Math.max(w / (float) bw, h / (float) bh);
        float dw = bw * scale;
        float dh = bh * scale;
        float left = (w - dw) / 2f + panX;
        float top = (h - dh) / 2f + panY;
        dstRect.set(left, top, left + dw, top + dh);
        canvas.drawBitmap(mapBmp, srcRect, dstRect, mapPaint);

        drawChunkGrid(canvas, bw, bh);

        float cx = dstRect.centerX();
        float cy = dstRect.centerY();
        drawArrow(canvas, cx, cy, meta == null ? 0f : meta.yaw);

        drawOverlay(canvas, w, h);
    }

    /** Faint grid every 16 source pixels (one Minecraft chunk). */
    private void drawChunkGrid(Canvas canvas, int bw, int bh) {
        if (bw <= 0 || bh <= 0) return;
        float stepX = dstRect.width() * 16f / bw;
        float stepY = dstRect.height() * 16f / bh;
        if (stepX < 4f || stepY < 4f) return;
        for (float x = dstRect.left + stepX; x < dstRect.right - 0.5f; x += stepX) {
            canvas.drawLine(x, dstRect.top, x, dstRect.bottom, gridPaint);
        }
        for (float y = dstRect.top + stepY; y < dstRect.bottom - 0.5f; y += stepY) {
            canvas.drawLine(dstRect.left, y, dstRect.right, y, gridPaint);
        }
    }

    /**
     * Arrow points north (up) at rotation 0. Canvas rotation is clockwise.
     * {@code canvasRotation = yaw + 180} so Minecraft yaw 0 (south) faces down.
     */
    private void drawArrow(Canvas canvas, float cx, float cy, float yaw) {
        float s = dp(14);
        arrow.reset();
        arrow.moveTo(0, -s);           // nose (north / up)
        arrow.lineTo(s * 0.62f, s * 0.72f);
        arrow.lineTo(0, s * 0.28f);    // notch
        arrow.lineTo(-s * 0.62f, s * 0.72f);
        arrow.close();

        arrowMatrix.reset();
        arrowMatrix.postRotate(yaw + 180f);
        arrowMatrix.postTranslate(cx, cy);
        arrow.transform(arrowMatrix);

        canvas.drawPath(arrow, arrowStroke);
        canvas.drawPath(arrow, arrowFill);
    }

    private void drawOverlay(Canvas canvas, int w, int h) {
        if (meta == null) return;
        String biome = shortName(meta.biome);
        String dim = shortName(meta.dim);
        String line = (int) Math.floor(meta.x) + " " + (int) Math.floor(meta.z)
                + (biome.isEmpty() ? "" : "  " + biome)
                + (dim.isEmpty() ? "" : "  " + dim);
        overlayPaint.setTextSize(dp(12));
        float pad = dp(6);
        float boxH = dp(20);
        canvas.drawRect(0, h - boxH, w, h, overlayBg);
        canvas.drawText(line, pad, h - dp(6), overlayPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                panning = true;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                lastPanAt = android.os.SystemClock.uptimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!panning) return false;
                panX += event.getX() - lastTouchX;
                panY += event.getY() - lastTouchY;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                lastPanAt = android.os.SystemClock.uptimeMillis();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                panning = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    static String shortName(String id) {
        if (id == null || id.isEmpty()) return "";
        int colon = id.lastIndexOf(':');
        String s = colon >= 0 ? id.substring(colon + 1) : id;
        return s.replace('_', ' ');
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Decode a map PNG off the UI thread. Caller must recycle. */
    public static Bitmap decodeMapPng(java.io.File file) {
        if (file == null || !file.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }
}
