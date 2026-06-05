package net.kdt.pojavlaunch.dualscreen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the player's inventory on the secondary-display deck by polling the JSON file written by
 * the companion Fabric mod (thor_deck/inventory.json). Spike: draws a slot grid with item short
 * names + counts (real item icons come later). Non-clickable so deck buttons above stay tappable.
 */
public class InventoryView extends View {
    private static final int COLS = 9;
    private static final int HOTBAR = 9;       // slots 0..8
    private static final int MAIN_END = 36;    // main inventory slots 0..35
    private static final long POLL_MS = 250;

    private File invFile;
    private long lastModified = -1;

    private HandlerThread pollThread;
    private Handler pollHandler;

    private final Paint cellFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cellStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    private final List<Slot> slots = new ArrayList<>();
    private volatile List<Slot> pending = null;

    private static final class Slot {
        int index;
        String name;
        int count;
    }

    public InventoryView(Context c) { super(c); init(); }
    public InventoryView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setClickable(false);
        setFocusable(false);
        cellFill.setColor(Color.parseColor("#33000000"));
        cellStroke.setStyle(Paint.Style.STROKE);
        cellStroke.setStrokeWidth(2f);
        cellStroke.setColor(Color.parseColor("#55FFFFFF"));
        namePaint.setColor(Color.parseColor("#FFEAEAEA"));
        countPaint.setColor(Color.parseColor("#FFFFE066"));
        countPaint.setFakeBoldText(true);
        headerPaint.setColor(Color.parseColor("#99FFFFFF"));
    }

    /** Provide the inventory JSON file written by the mod. */
    public void setInventoryFile(File f) {
        this.invFile = f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pollThread = new HandlerThread("thor-inv-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        pollHandler.post(poll);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pollHandler != null) pollHandler.removeCallbacksAndMessages(null);
        if (pollThread != null) pollThread.quitSafely();
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
                            pending = parsed;
                            postInvalidate();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            if (pollHandler != null) pollHandler.postDelayed(this, POLL_MS);
        }
    };

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
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.getJSONArray("slots");
            List<Slot> result = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Slot s = new Slot();
                s.index = o.optInt("i", -1);
                s.count = o.optInt("c", 0);
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

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // Lay out the grid in the lower portion so deck buttons stay visible/tappable up top.
        float topInset = Math.min(h * 0.30f, dp(150));
        float gridTop = topInset;
        float cell = Math.min(w / (float) COLS, (h - gridTop) / 5f); // 4 rows + 1 spacer
        float gridW = cell * COLS;
        float left = (w - gridW) / 2f;

        headerPaint.setTextSize(cell * 0.30f);
        canvas.drawText("Inventory", left, gridTop - dp(8), headerPaint);

        // Map slot index -> data
        Slot[] byIndex = new Slot[MAIN_END];
        for (Slot s : slots) {
            if (s.index >= 0 && s.index < MAIN_END) byIndex[s.index] = s;
        }

        namePaint.setTextSize(cell * 0.20f);
        countPaint.setTextSize(cell * 0.26f);

        // Main inventory rows 9..35 (3 rows), then hotbar 0..8 at the bottom (like the real GUI).
        int[] order = new int[MAIN_END];
        int p = 0;
        for (int i = HOTBAR; i < MAIN_END; i++) order[p++] = i; // 9..35
        for (int i = 0; i < HOTBAR; i++) order[p++] = i;        // 0..8 (hotbar last row)

        for (int pos = 0; pos < MAIN_END; pos++) {
            int slotIndex = order[pos];
            int row = pos / COLS;
            int col = pos % COLS;
            float x = left + col * cell;
            float y = gridTop + row * cell + (row == 3 ? dp(6) : 0); // gap before hotbar row
            drawCell(canvas, x, y, cell, byIndex[slotIndex]);
        }
    }

    private void drawCell(Canvas canvas, float x, float y, float cell, Slot s) {
        float pad = cell * 0.06f;
        tmpRect.set(x + pad, y + pad, x + cell - pad, y + cell - pad);
        canvas.drawRoundRect(tmpRect, dp(4), dp(4), cellFill);
        canvas.drawRoundRect(tmpRect, dp(4), dp(4), cellStroke);
        if (s == null || s.count <= 0) return;

        // Item short name, truncated to fit.
        String name = s.name;
        float maxW = cell - pad * 2 - dp(4);
        while (name.length() > 3 && namePaint.measureText(name) > maxW) {
            name = name.substring(0, name.length() - 1);
        }
        canvas.drawText(name, x + pad + dp(3), y + cell * 0.45f, namePaint);
        // Count, bottom-right.
        String c = Integer.toString(s.count);
        float cw = countPaint.measureText(c);
        canvas.drawText(c, x + cell - pad - cw - dp(3), y + cell - pad - dp(4), countPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
