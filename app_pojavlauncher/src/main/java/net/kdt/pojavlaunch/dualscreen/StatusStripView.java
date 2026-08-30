package net.kdt.pojavlaunch.dualscreen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Compact 3DS-style HUD strip: hearts, hunger pips, XP level, biome + xz + Day/Night.
 * Data is parsed off the UI thread from {@code hud.json} and handed in via {@link #setHud}.
 */
public class StatusStripView extends View {
    private static final int HEARTS = 10;
    private static final int PIPS = 10;

    private final Paint bgPaint = new Paint();
    private final Paint edgePaint = new Paint();
    private final Paint heartFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint heartEmpty = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint heartStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hungerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hungerEmpty = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heart = new Path();
    private final RectF tmp = new RectF();

    private HudState hud;

    public static final class HudState {
        public long seq;
        public float hp = 20f;
        public float maxHp = 20f;
        public int hunger = 20;
        public float saturation;
        public int air;
        public float xp;
        public int level;
        public int armor;
        public double x, y, z;
        public float yaw, pitch;
        public String biome = "";
        public String dim = "";
        public long time;
        public long dayTime;
        public String weather = "";
    }

    public StatusStripView(Context c) { super(c); init(); }
    public StatusStripView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        bgPaint.setColor(0xFF0C0903); // near-black oak from mock HUD band rgb(12,9,3)
        edgePaint.setColor(0xFF1A1A1A);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(2f);

        heartFill.setColor(0xFFE03030);
        heartFill.setStyle(Paint.Style.FILL);
        heartEmpty.setColor(0xFF3D3D3D);
        heartEmpty.setStyle(Paint.Style.FILL);
        heartStroke.setColor(0xFF1A1A1A);
        heartStroke.setStyle(Paint.Style.STROKE);
        heartStroke.setStrokeWidth(1.4f);

        hungerFill.setColor(0xFFC07028);
        hungerFill.setStyle(Paint.Style.FILL);
        hungerEmpty.setColor(0xFF3D3D3D);
        hungerEmpty.setStyle(Paint.Style.FILL);

        xpPaint.setColor(0xFF80FF20);
        xpPaint.setFakeBoldText(true);
        infoPaint.setColor(0xFFE0E0E0);
        infoPaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setHud(HudState state) {
        this.hud = state;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(0xFF0C0903); // dark HUD panel, not brown oak / gray rectangle
        canvas.drawLine(0, h - 1, w, h - 1, edgePaint);

        HudState s = hud;
        float hp = s == null ? 0 : s.hp;
        float maxHp = s == null || s.maxHp <= 0 ? 20f : s.maxHp;
        int hunger = s == null ? 0 : s.hunger;
        int level = s == null ? 0 : s.level;

        float pad = dp(6);
        float heartSize = Math.min(dp(12), h - dp(10));
        float x = pad;
        float y = (h - heartSize) / 2f;
        float ratio = Math.max(0f, Math.min(1f, hp / maxHp));
        float filledHearts = ratio * HEARTS;
        for (int i = 0; i < HEARTS; i++) {
            float frac = filledHearts - i;
            drawHeart(canvas, x + i * (heartSize + dp(1.5f)), y, heartSize, frac);
        }

        x += HEARTS * (heartSize + dp(1.5f)) + dp(8);
        float pipW = dp(7);
        float pipH = dp(9);
        float pipY = (h - pipH) / 2f;
        float filledPips = Math.max(0f, Math.min(PIPS, hunger / 2f));
        for (int i = 0; i < PIPS; i++) {
            float px = x + i * (pipW + dp(1.5f));
            tmp.set(px, pipY, px + pipW, pipY + pipH);
            float frac = filledPips - i;
            canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), hungerEmpty);
            if (frac >= 1f) {
                canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), hungerFill);
            } else if (frac > 0f) {
                canvas.save();
                canvas.clipRect(tmp.left, tmp.top, tmp.left + tmp.width() * frac, tmp.bottom);
                canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), hungerFill);
                canvas.restore();
            }
        }

        x += PIPS * (pipW + dp(1.5f)) + dp(10);
        xpPaint.setTextSize(dp(12));
        canvas.drawText("Lv " + level, x, h / 2f + dp(4), xpPaint);

        String info = buildInfo(s);
        infoPaint.setTextSize(dp(11));
        canvas.drawText(info, w - pad, h / 2f + dp(4), infoPaint);
    }

    private void drawHeart(Canvas canvas, float left, float top, float size, float frac) {
        buildHeart(heart, left, top, size);
        canvas.drawPath(heart, heartEmpty);
        if (frac >= 0.99f) {
            canvas.drawPath(heart, heartFill);
        } else if (frac > 0.05f) {
            canvas.save();
            canvas.clipRect(left, top, left + size * Math.min(1f, frac), top + size);
            canvas.drawPath(heart, heartFill);
            canvas.restore();
        }
        canvas.drawPath(heart, heartStroke);
    }

    private static void buildHeart(Path p, float x, float y, float s) {
        p.reset();
        float cx = x + s * 0.5f;
        p.moveTo(cx, y + s * 0.88f);
        p.cubicTo(x - s * 0.12f, y + s * 0.50f, x + s * 0.05f, y - s * 0.02f, cx, y + s * 0.32f);
        p.cubicTo(x + s * 0.95f, y - s * 0.02f, x + s * 1.12f, y + s * 0.50f, cx, y + s * 0.88f);
        p.close();
    }

    private static String buildInfo(HudState s) {
        if (s == null) return "\u2014";
        String biome = MinimapView.shortName(s.biome);
        int ix = (int) Math.floor(s.x);
        int iz = (int) Math.floor(s.z);
        String timeWord = timeWord(s);
        StringBuilder sb = new StringBuilder();
        if (!biome.isEmpty()) sb.append(biome).append("  ");
        sb.append(ix).append(" ").append(iz).append("  ").append(timeWord);
        return sb.toString();
    }

    /** Minecraft dayTime 0 = sunrise, 6000 = noon, 13000 = night, 18000 = midnight. */
    static String timeWord(HudState s) {
        long t = s.dayTime != 0 ? s.dayTime : s.time;
        t = t % 24000L;
        if (t < 0) t += 24000L;
        return (t >= 0 && t < 13000L) ? "Day" : "Night";
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
