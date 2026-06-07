package net.kdt.pojavlaunch.dualscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders the player's inventory on the secondary-display deck by polling the JSON file written by
 * the companion Fabric mod (thor_deck/inventory.json), drawing real item icons (PNGs the mod
 * exports to thor_deck/icons/), and sending slot taps back via thor_deck/command.json (tap-to-move).
 */
public class InventoryView extends View {
    private static final int COLS = 9;
    private static final int HOTBAR = 9;     // slots 0..8
    private static final int MAIN_END = 36;  // main inventory slots 0..35
    private static final long POLL_MS = 200;

    private File invFile;
    private File iconDir;
    private File commandFile;
    private long lastModified = -1;
    private long commandSeq = 0;

    private HandlerThread ioThread;
    private Handler ioHandler;

    private final Paint cellFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cellStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint();
    private final RectF tmpRect = new RectF();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private final List<Slot> slots = new ArrayList<>();
    private volatile List<Slot> pending = null;
    private final ConcurrentHashMap<String, Bitmap> iconCache = new ConcurrentHashMap<>();

    // Grid geometry (computed in onDraw, used by touch hit-testing).
    private float gLeft, gTop, gCell;
    private int[] order;
    private final Slot[] byIndex = new Slot[MAIN_END];

    // Drag-and-drop state
    private int dragFrom = -1;
    private float dragX, dragY;
    private Bitmap dragBmp;
    private String dragName;

    private static final class Slot {
        int index;
        String name;
        String icon;
        int count;
    }

    public InventoryView(Context c) { super(c); init(); }
    public InventoryView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        iconPaint.setFilterBitmap(false); // crisp pixel-art scaling
        cellFill.setColor(Color.parseColor("#40000000"));
        cellStroke.setStyle(Paint.Style.STROKE);
        cellStroke.setStrokeWidth(2f);
        cellStroke.setColor(Color.parseColor("#55FFFFFF"));
        namePaint.setColor(Color.parseColor("#FFEAEAEA"));
        countPaint.setColor(Color.WHITE);
        countPaint.setFakeBoldText(true);
        countPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        headerPaint.setColor(Color.parseColor("#99FFFFFF"));

        // Build slot draw order: main inv 9..35 (top 3 rows), hotbar 0..8 (bottom row).
        order = new int[MAIN_END];
        int p = 0;
        for (int i = HOTBAR; i < MAIN_END; i++) order[p++] = i;
        for (int i = 0; i < HOTBAR; i++) order[p++] = i;
    }

    public void setInventoryFile(File f) { this.invFile = f; }
    public void setIconDir(File d) { this.iconDir = d; }
    public void setCommandFile(File f) { this.commandFile = f; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ioThread = new HandlerThread("thor-inv-io");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        ioHandler.post(poll);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (ioHandler != null) ioHandler.removeCallbacksAndMessages(null);
        if (ioThread != null) ioThread.quitSafely();
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                if (invFile != null && invFile.exists()) {
                    long mod = invFile.lastModified();
                    if (mod != lastModified) {
                        lastModified = mod;
                        List<Slot> parsed = parse(readFile(invFile));
                        if (parsed != null) {
                            for (Slot s : parsed) loadIcon(s.icon);
                            pending = parsed;
                            postInvalidate();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            if (ioHandler != null) ioHandler.postDelayed(this, POLL_MS);
        }
    };

    private void loadIcon(String icon) {
        if (icon == null || icon.isEmpty() || iconDir == null) return;
        if (iconCache.containsKey(icon)) return;
        File f = new File(iconDir, icon + ".png");
        if (!f.exists()) return;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bmp != null) iconCache.put(icon, bmp);
        } catch (Exception ignored) {
        }
    }

    private static String readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static List<Slot> parse(String json) {
        try {
            JSONArray arr = new JSONObject(json).getJSONArray("slots");
            List<Slot> result = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Slot s = new Slot();
                s.index = o.optInt("i", -1);
                s.count = o.optInt("c", 0);
                s.icon = o.optString("icon", "");
                String id = o.optString("id", "");
                int colon = id.indexOf(':');
                s.name = colon >= 0 ? id.substring(colon + 1) : id;
                result.add(s);
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pending != null) {
            slots.clear();
            slots.addAll(pending);
            pending = null;
        }
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        float topInset = Math.min(h * 0.30f, dp(150));
        gTop = topInset;
        gCell = Math.min(w / (float) COLS, (h - gTop) / 5f);
        float gridW = gCell * COLS;
        gLeft = (w - gridW) / 2f;

        headerPaint.setTextSize(gCell * 0.30f);
        canvas.drawText("Inventory", gLeft, gTop - dp(8), headerPaint);

        java.util.Arrays.fill(byIndex, null);
        for (Slot s : slots) if (s.index >= 0 && s.index < MAIN_END) byIndex[s.index] = s;

        namePaint.setTextSize(gCell * 0.18f);
        countPaint.setTextSize(gCell * 0.28f);

        for (int pos = 0; pos < MAIN_END; pos++) {
            int row = pos / COLS, col = pos % COLS;
            float x = gLeft + col * gCell;
            float y = gTop + row * gCell + (row == 3 ? dp(6) : 0);
            // Dim the source slot while dragging.
            drawCell(canvas, x, y, order[pos] == dragFrom ? null : byIndex[order[pos]]);
        }

        // Floating item under the finger while dragging.
        if (dragFrom >= 0) {
            float half = gCell * 0.40f;
            if (dragBmp != null) {
                srcRect.set(0, 0, dragBmp.getWidth(), dragBmp.getHeight());
                dstRect.set(dragX - half, dragY - half, dragX + half, dragY + half);
                canvas.drawBitmap(dragBmp, srcRect, dstRect, iconPaint);
            } else if (dragName != null) {
                canvas.drawText(dragName, dragX - half, dragY, namePaint);
            }
        }
    }

    private void drawCell(Canvas canvas, float x, float y, Slot s) {
        float pad = gCell * 0.06f;
        tmpRect.set(x + pad, y + pad, x + gCell - pad, y + gCell - pad);
        canvas.drawRoundRect(tmpRect, dp(4), dp(4), cellFill);
        canvas.drawRoundRect(tmpRect, dp(4), dp(4), cellStroke);
        if (s == null || s.count <= 0) return;

        Bitmap bmp = s.icon == null ? null : iconCache.get(s.icon);
        if (bmp != null) {
            float ipad = gCell * 0.14f;
            srcRect.set(0, 0, bmp.getWidth(), bmp.getHeight());
            dstRect.set(x + ipad, y + ipad, x + gCell - ipad, y + gCell - ipad);
            canvas.drawBitmap(bmp, srcRect, dstRect, iconPaint);
        } else {
            // Fallback: truncated item name.
            String name = s.name;
            float maxW = gCell - pad * 2 - dp(4);
            while (name.length() > 3 && namePaint.measureText(name) > maxW) {
                name = name.substring(0, name.length() - 1);
            }
            canvas.drawText(name, x + pad + dp(3), y + gCell * 0.5f, namePaint);
        }
        if (s.count > 1) {
            String c = Integer.toString(s.count);
            float cw = countPaint.measureText(c);
            canvas.drawText(c, x + gCell - pad - cw - dp(2), y + gCell - pad - dp(3), countPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int slot = hitTest(event.getX(), event.getY());
                if (slot < 0) return false; // outside grid -> let deck buttons handle it
                Slot s = byIndex[slot];
                if (s == null || s.count <= 0) return false; // empty slot -> nothing to drag
                dragFrom = slot;
                dragX = event.getX();
                dragY = event.getY();
                dragBmp = s.icon == null ? null : iconCache.get(s.icon);
                dragName = s.name;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragFrom < 0) return false;
                dragX = event.getX();
                dragY = event.getY();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (dragFrom < 0) return false;
                int from = dragFrom;
                int dest = hitTest(event.getX(), event.getY());
                clearDrag();
                if (dest >= 0 && dest != from) sendMove(from, dest);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                clearDrag();
                invalidate();
                return true;
            }
        }
        return false;
    }

    private void clearDrag() {
        dragFrom = -1;
        dragBmp = null;
        dragName = null;
    }

    /** Map a touch point to an inventory slot index, or -1 if outside the grid. */
    private int hitTest(float px, float py) {
        if (gCell <= 0) return -1;
        for (int pos = 0; pos < MAIN_END; pos++) {
            int row = pos / COLS, col = pos % COLS;
            float x = gLeft + col * gCell;
            float y = gTop + row * gCell + (row == 3 ? dp(6) : 0);
            if (px >= x && px < x + gCell && py >= y && py < y + gCell) {
                return order[pos];
            }
        }
        return -1;
    }

    private void sendMove(int from, int to) {
        if (commandFile == null || ioHandler == null) return;
        final long seq = ++commandSeq;
        ioHandler.post(() -> {
            String json = "{\"seq\":" + seq + ",\"from\":" + from + ",\"to\":" + to + ",\"button\":0}";
            try (FileOutputStream out = new FileOutputStream(commandFile)) {
                out.write(json.getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
        });
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
