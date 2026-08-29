package net.kdt.pojavlaunch.dualscreen;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Bottom-screen chrome is the mock art. Tabs swap skins. Fills the Presentation (no letterbox).
 * Live map/inv/chat are punched in by overlays on top; this view owns the wood/stone and tab hits.
 */
public class SkinDeckView extends View {
    public interface Listener { void onTab(int index); }

    private final Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private Bitmap skinMap, skinInv, skinChat;
    private int tab = 0;
    private Listener listener;

    public SkinDeckView(Context c) { super(c); init(); }
    public SkinDeckView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        smooth.setFilterBitmap(true);
        skinMap = decodeSkin("map");
        skinInv = decodeSkin("inv");
        skinChat = decodeSkin("chat");
        setClickable(true);
        setFocusable(false);
    }

    private Bitmap decodeSkin(String stem) {
        int id = getResources().getIdentifier("skin_" + stem, "drawable", getContext().getPackageName());
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (id != 0) {
            Bitmap b = BitmapFactory.decodeResource(getResources(), id, o);
            if (b != null) return b;
        }
        StringBuilder sb = new StringBuilder();
        AssetManager am = getContext().getAssets();
        for (int i = 0; i < 8; i++) {
            String path = "thor_skins/skin_" + stem + String.format(Locale.US, ".%02d.b64", i);
            try (InputStream in = am.open(path)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[4096];
                int n;
                while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
                sb.append(new String(buf.toByteArray(), StandardCharsets.US_ASCII));
            } catch (IOException e) {
                break;
            }
        }
        if (sb.length() == 0) return null;
        byte[] raw = Base64.decode(sb.toString().trim(), Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }

    public void setListener(Listener l) { listener = l; }

    public void setTab(int t) {
        if (t == tab) return;
        tab = t;
        invalidate();
    }

    public int getTab() { return tab; }

    private Bitmap currentSkin() {
        if (tab == 1) return skinInv;
        if (tab == 2) return skinChat;
        return skinMap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(0xFF1A140E);
        Bitmap skin = currentSkin();
        if (skin != null && !skin.isRecycled()) {
            src.set(0, 0, skin.getWidth(), skin.getHeight());
            dst.set(0, 0, w, h);
            canvas.drawBitmap(skin, src, dst, smooth);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) {
            return event.getActionMasked() == MotionEvent.ACTION_DOWN;
        }
        float x = event.getX();
        float y = event.getY();
        if (y >= getHeight() * 0.78f) {
            float nx = x / Math.max(1, getWidth());
            int t = nx < 0.38f ? 0 : nx < 0.66f ? 1 : 2;
            setTab(t);
            if (listener != null) listener.onTab(t);
            return true;
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        recycle(skinMap); skinMap = null;
        recycle(skinInv); skinInv = null;
        recycle(skinChat); skinChat = null;
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }
}
