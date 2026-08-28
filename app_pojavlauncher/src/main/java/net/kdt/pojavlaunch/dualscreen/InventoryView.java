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
 *
 * Layout is Minecraft / 3DS-style: 3x9 main inventory (slots 9-35), a gapped hotbar (0-8), and
 * optionally a left armor column (36-39) plus offhand (40) when those indices appear in the JSON.
 */
public class InventoryView extends View {
    private static final int COLS = 9;
    private static final int HOTBAR = 9;      // slots 0..8
    private static final int ARMOR_BOOTS = 36;
    private static final int ARMOR_LEGS = 37;
    private static final int ARMOR_CHEST = 38;
    private static final int ARMOR_HELMET = 39;
    private static final int OFFHAND = 40;
    private static final int SLOT_CAP = 41;   // indices 0..40
    private static final long POLL_MS = 200;

    /** Visual order of armor, helmet at the top. */
    private static final int[] ARMOR_ORDER = new int[] {
            ARMOR_HELMET, ARMOR_CHEST, ARMOR_LEGS, ARMOR_BOOTS
    };
    private static final String[] ARMOR_HINT = new String[] { "H", "C", "L", "B" };

    private File invFile;
    private File iconDir;
    private File commandFile;
    private long lastModified = -1;
    private long commandSeq = 0;

    private HandlerThread ioThread;
    private Handler ioHandler;

    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cellFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cellStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bevelDark = new Paint();
    private final Paint bevelLight = new Paint();
    private final Paint hotbarTray = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptySubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint();
    private final RectF tmpRect = new RectF();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private final List<Slot> slots = new ArrayList<>();
    private volatile Snapshot pending = null;
    private final ConcurrentHashMap<String, Bitmap> iconCache = new ConcurrentHashMap<>();

    /** True once we have seen inventory.json; false shows the install-mod empty state. */
    private volatile boolean filePresent = false;
    /** IO-thread: whether we already posted the missing-file snapshot (avoid 200ms redraws). */
    private boolean announcedMissing = false;

    private int selectedHotbar = -1;
    private boolean hasArmor = false;
    private boolean hasOffhand = false;

    private final Slot[] byIndex = new Slot[SLOT_CAP];
    private final ArrayList<Cell> cells = new ArrayList<>();

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

    private static final class Snapshot {
        final List<Slot> slots;
        final int selected;
        final boolean filePresent;
        Snapshot(List<Slot> slots, int selected, boolean filePresent) {
            this.slots = slots;
            this.selected = selected;
            this.filePresent = filePresent;
        }
    }

    private static final class Cell {
        int index;
        float x, y, size;
        boolean hotbar;
        String hint;
        Cell(int index, float x, float y, float size, boolean hotbar, String hint) {
            this.index = index;
            this.x = x;
            this.y = y;
            this.size = size;
            this.hotbar = hotbar;
            this.hint = hint;
        }
    }

    public InventoryView(Context c) { super(c); init(); }
    public InventoryView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        iconPaint.setFilterBitmap(false); // crisp pixel-art scaling

        panelPaint.setColor(Color.parseColor("#2B2B2B"));
        panelStroke.setStyle(Paint.Style.STROKE);
        panelStroke.setStrokeWidth(2f);
        panelStroke.setColor(Color.parseColor("#1A1A1A"));

        // Classic MC slot: fill ~#8B8B8B, border ~#C6C6C6
        cellFill.setColor(Color.parseColor("#8B8B8B"));
        cellStroke.setStyle(Paint.Style.STROKE);
        cellStroke.setStrokeWidth(1.5f);
        cellStroke.setColor(Color.parseColor("#C6C6C6"));

        bevelDark.setColor(Color.parseColor("#373737"));
        bevelDark.setStyle(Paint.Style.STROKE);
        bevelDark.setStrokeWidth(2f);
        bevelLight.setColor(Color.parseColor("#C6C6C6"));
        bevelLight.setStyle(Paint.Style.STROKE);
        bevelLight.setStrokeWidth(2f);

        hotbarTray.setColor(Color.parseColor("#3A3A3A"));

        namePaint.setColor(Color.parseColor("#FFEAEAEA"));
        countPaint.setColor(Color.WHITE);
        countPaint.setFakeBoldText(true);
        countPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        headerPaint.setColor(Color.parseColor("#E0E0E0"));
        headerPaint.setFakeBoldText(true);
        hintPaint.setColor(Color.parseColor("#66373737"));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setColor(Color.parseColor("#DDDDDD"));
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        emptySubPaint.setColor(Color.parseColor("#99CCCCCC"));
        emptySubPaint.setTextAlign(Paint.Align.CENTER);

        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setColor(Color.parseColor("#FFFFFF55"));
        selectedPaint.setStrokeWidth(3f);
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
                boolean exists = invFile != null && invFile.exists();
                if (!exists) {
                    if (!announcedMissing) {
                        announcedMissing = true;
                        lastModified = -1;
                        pending = new Snapshot(null, -1, false);
                        postInvalidate();
                    }
                } else {
                    announcedMissing = false;
                    long mod = invFile.lastModified();
                    if (mod != lastModified) {
                        lastModified = mod;
                        Snapshot parsed = parse(readFile(invFile));
                        if (parsed != null) {
                            for (int i = 0; i < parsed.slots.size(); i++) {
                                loadIcon(parsed.slots.get(i).icon);
                            }
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
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static Snapshot parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            int selected = root.optInt("selected", -1);
            if (selected < 0 || selected >= HOTBAR) selected = -1;
            JSONArray arr = root.getJSONArray("slots");
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
            return new Snapshot(result, selected, true);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Snapshot snap = pending;
        if (snap != null) {
            pending = null;
            filePresent = snap.filePresent;
            selectedHotbar = snap.selected;
            slots.clear();
            if (snap.slots != null) slots.addAll(snap.slots);
            else slots.clear();
        }

        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // Dark stone page background.
        canvas.drawColor(Color.parseColor("#1E1E1E"));

        if (!filePresent) {
            drawEmptyState(canvas, w, h);
            cells.clear();
            return;
        }

        java.util.Arrays.fill(byIndex, null);
        hasArmor = false;
        hasOffhand = false;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s.index >= 0 && s.index < SLOT_CAP) byIndex[s.index] = s;
            if (s.index >= ARMOR_BOOTS && s.index <= ARMOR_HELMET) hasArmor = true;
            if (s.index == OFFHAND) hasOffhand = true;
        }

        rebuildLayout(w, h);
        drawPanel(canvas);
        drawHotbarTray(canvas);

        headerPaint.setTextSize(dp(14));
        if (!cells.isEmpty()) {
            canvas.drawText("Inventory", cells.get(0).x, Math.max(dp(16), cells.get(0).y - dp(8)), headerPaint);
        }

        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            Slot s = byIndex[cell.index];
            boolean dim = (cell.index == dragFrom);
            boolean selected = cell.hotbar && selectedHotbar >= 0 && cell.index == selectedHotbar;
            drawCell(canvas, cell, dim ? null : s, selected);
        }

        // Floating item under the finger while dragging.
        if (dragFrom >= 0) {
            float half = dp(18);
            // Prefer the cell size if we can find it.
            for (int i = 0; i < cells.size(); i++) {
                if (cells.get(i).index == dragFrom) {
                    half = cells.get(i).size * 0.40f;
                    break;
                }
            }
            if (dragBmp != null) {
                srcRect.set(0, 0, dragBmp.getWidth(), dragBmp.getHeight());
                dstRect.set(dragX - half, dragY - half, dragX + half, dragY + half);
                canvas.drawBitmap(dragBmp, srcRect, dstRect, iconPaint);
            } else if (dragName != null) {
                canvas.drawText(dragName, dragX - half, dragY, namePaint);
            }
        }
    }

    private void drawEmptyState(Canvas canvas, int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;
        emptyPaint.setTextSize(dp(16));
        emptySubPaint.setTextSize(dp(13));
        canvas.drawText("Install thor-deck-mod to see inventory", cx, cy - dp(6), emptyPaint);
        canvas.drawText("Waiting for inventory.json", cx, cy + dp(16), emptySubPaint);
    }

    private void rebuildLayout(int w, int h) {
        cells.clear();
        float pad = dp(10);
        float header = dp(22);
        float contentW = w - pad * 2;
        float contentH = h - pad - header;
        if (contentW <= 0 || contentH <= 0) return;

        boolean armorCol = hasArmor || hasOffhand;
        float armorGapUnits = armorCol ? 0.40f : 0f;
        float unitsX = COLS + (armorCol ? 1f + armorGapUnits : 0f);
        float hotbarGapUnits = 0.40f;
        float unitsY = 3f + hotbarGapUnits + 1f;

        float cell = Math.min(contentW / unitsX, contentH / unitsY);
        if (cell <= 0) return;

        float gridW = cell * unitsX;
        float gridH = cell * unitsY;
        float left = (w - gridW) / 2f;
        float top = header + (h - header - pad - gridH) / 2f;
        if (top < header) top = header;

        float mainX = left + (armorCol ? cell * (1f + armorGapUnits) : 0f);

        // Main 3x9 (slots 9-35)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = HOTBAR + row * COLS + col;
                cells.add(new Cell(index, mainX + col * cell, top + row * cell, cell, false, null));
            }
        }

        // Hotbar (0-8) with a gap above it
        float hotbarY = top + 3f * cell + hotbarGapUnits * cell;
        for (int col = 0; col < COLS; col++) {
            cells.add(new Cell(col, mainX + col * cell, hotbarY, cell, true, null));
        }

        // Armor column: 4 slots packed into the 3-row main height (helmet on top).
        if (hasArmor) {
            float armorSize = cell * 3f / 4f;
            float armorX = left + (cell - armorSize) / 2f;
            for (int i = 0; i < ARMOR_ORDER.length; i++) {
                float y = top + i * armorSize;
                cells.add(new Cell(ARMOR_ORDER[i], armorX, y, armorSize, false, ARMOR_HINT[i]));
            }
        }

        // Offhand sits in the armor column on the hotbar row.
        if (hasOffhand) {
            cells.add(new Cell(OFFHAND, left, hotbarY, cell, false, "OH"));
        }
    }

    private void drawPanel(Canvas canvas) {
        if (cells.isEmpty()) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = 0, maxY = 0;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (c.x < minX) minX = c.x;
            if (c.y < minY) minY = c.y;
            if (c.x + c.size > maxX) maxX = c.x + c.size;
            if (c.y + c.size > maxY) maxY = c.y + c.size;
        }
        float inset = dp(8);
        tmpRect.set(minX - inset, minY - inset - dp(18), maxX + inset, maxY + inset);
        canvas.drawRoundRect(tmpRect, dp(6), dp(6), panelPaint);
        canvas.drawRoundRect(tmpRect, dp(6), dp(6), panelStroke);
    }

    private void drawHotbarTray(Canvas canvas) {
        Cell first = null, last = null;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (!c.hotbar) continue;
            if (first == null) first = c;
            last = c;
        }
        if (first == null || last == null) return;
        float inset = dp(3);
        tmpRect.set(first.x - inset, first.y - inset,
                last.x + last.size + inset, last.y + last.size + inset);
        canvas.drawRoundRect(tmpRect, dp(3), dp(3), hotbarTray);
    }

    private void drawCell(Canvas canvas, Cell cell, Slot s, boolean selected) {
        float x = cell.x, y = cell.y, size = cell.size;
        float pad = size * 0.06f;
        tmpRect.set(x + pad, y + pad, x + size - pad, y + size - pad);

        canvas.drawRect(tmpRect, cellFill);

        // 3D inset bevel: dark top/left, light bottom/right.
        float l = tmpRect.left, t = tmpRect.top, r = tmpRect.right, b = tmpRect.bottom;
        canvas.drawLine(l, t, r, t, bevelDark);
        canvas.drawLine(l, t, l, b, bevelDark);
        canvas.drawLine(l, b, r, b, bevelLight);
        canvas.drawLine(r, t, r, b, bevelLight);
        canvas.drawRect(tmpRect, cellStroke);

        if (selected) {
            selectedPaint.setStrokeWidth(Math.max(2f, size * 0.06f));
            canvas.drawRect(tmpRect, selectedPaint);
        }

        boolean empty = (s == null || s.count <= 0);
        if (empty) {
            if (cell.hint != null) {
                hintPaint.setTextSize(size * 0.32f);
                canvas.drawText(cell.hint, x + size / 2f, y + size * 0.62f, hintPaint);
            }
            return;
        }

        namePaint.setTextSize(size * 0.18f);
        countPaint.setTextSize(size * 0.28f);

        Bitmap bmp = s.icon == null ? null : iconCache.get(s.icon);
        if (bmp != null) {
            float ipad = size * 0.14f;
            srcRect.set(0, 0, bmp.getWidth(), bmp.getHeight());
            dstRect.set(x + ipad, y + ipad, x + size - ipad, y + size - ipad);
            canvas.drawBitmap(bmp, srcRect, dstRect, iconPaint);
        } else {
            // Fallback: truncated item name.
            String name = s.name == null ? "" : s.name;
            float maxW = size - pad * 2 - dp(4);
            while (name.length() > 3 && namePaint.measureText(name) > maxW) {
                name = name.substring(0, name.length() - 1);
            }
            canvas.drawText(name, x + pad + dp(3), y + size * 0.5f, namePaint);
        }
        if (s.count > 1) {
            String c = Integer.toString(s.count);
            float cw = countPaint.measureText(c);
            canvas.drawText(c, x + size - pad - cw - dp(2), y + size - pad - dp(3), countPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int slot = hitTest(event.getX(), event.getY());
                if (slot < 0) return false; // outside grid -> do not consume
                Slot s = slot < SLOT_CAP ? byIndex[slot] : null;
                // Empty slot: do not start a drag (ACTION_UP of a real drag can still target it).
                if (s == null || s.count <= 0) return false;
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
                // dest may be an EMPTY slot — still a valid drop target.
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

    /** Map a touch point to an inventory slot index, or -1 if outside any cell. */
    private int hitTest(float px, float py) {
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (px >= c.x && px < c.x + c.size && py >= c.y && py < c.y + c.size) {
                return c.index;
            }
        }
        return -1;
    }

    private void sendMove(int from, int to) {
        if (commandFile == null || ioHandler == null) return;
        final long seq = ++commandSeq;
        ioHandler.post(new Runnable() {
            @Override
            public void run() {
                String json = "{\"seq\":" + seq + ",\"from\":" + from + ",\"to\":" + to + ",\"button\":0}";
                try {
                    FileOutputStream out = new FileOutputStream(commandFile);
                    try {
                        out.write(json.getBytes("UTF-8"));
                    } finally {
                        out.close();
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
