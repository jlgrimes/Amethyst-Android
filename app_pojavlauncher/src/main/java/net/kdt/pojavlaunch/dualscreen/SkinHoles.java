package net.kdt.pojavlaunch.dualscreen;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Punch-through holes on the 1536×1024 oak-and-stone skins.
 *
 * Coordinates are native skin pixels (inScaled=false). Overlay layout scales them
 * into {@link SkinDeckView#getSkinDest(RectF)} so letterboxing never stretches circles.
 *
 * Measured 2026-08-30 from mocks/mock-{map,inv,chat}.png (harness-proven):
 * <ul>
 *   <li>MAP — inner leather window (terrain, not the stitch/rivet frame)</li>
 *   <li>INV — armor column + 3×9 + hotbar, below the painted HUD strip</li>
 *   <li>CHAT — parchment, above the tab dock</li>
 * </ul>
 */
public final class SkinHoles {
    public static final int SKIN_W = 1536;
    public static final int SKIN_H = 1024;

    /** Inner map (covers painted village). Skin px: l,t,r,b */
    public static final Rect MAP = new Rect(208, 312, 1336, 800);
    /** Inventory grid (covers painted stacks). */
    public static final Rect INV = new Rect(144, 268, 1408, 824);
    /** Chat parchment (covers painted lines). */
    public static final Rect CHAT = new Rect(210, 327, 1305, 840);
    /**
     * Persistent HP/hunger/coords strip (covers painted mock HUD on every tab).
     * Measured 2026-08-30 / harness-proven: hearts ~168-747x189-221, gold coords to x~1347;
     * utility bar ends ~y120; leather map frame starts ~y280.
     */
    public static final Rect HUD = new Rect(144, 168, 1408, 256);

    private SkinHoles() {}

    public static Rect forTab(int tab) {
        if (tab == 1) return INV;
        if (tab == 2) return CHAT;
        return MAP;
    }

    /**
     * Map a skin-pixel hole into view pixels given the letterboxed skin dest.
     * Uniform scale (fit-center) so circles stay circles.
     */
    public static Rect scale(RectF dest, Rect hole) {
        if (dest == null || dest.width() <= 0 || dest.height() <= 0) {
            return new Rect(hole);
        }
        float s = dest.width() / (float) SKIN_W;
        int l = Math.round(dest.left + hole.left * s);
        int t = Math.round(dest.top + hole.top * s);
        int r = Math.round(dest.left + hole.right * s);
        int b = Math.round(dest.top + hole.bottom * s);
        return new Rect(l, t, r, b);
    }

    public static void place(View view, RectF dest, Rect hole) {
        if (view == null || !(view.getParent() instanceof FrameLayout)) return;
        Rect px = scale(dest, hole);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(px.width(), px.height());
        }
        int w = Math.max(1, px.width());
        int h = Math.max(1, px.height());
        int g = android.view.Gravity.TOP | android.view.Gravity.START;
        if (lp.width == w && lp.height == h && lp.leftMargin == px.left && lp.topMargin == px.top
                && lp.gravity == g) {
            return;
        }
        lp.width = w;
        lp.height = h;
        lp.leftMargin = px.left;
        lp.topMargin = px.top;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        lp.gravity = g;
        view.setLayoutParams(lp);
    }
}
