package net.kdt.pojavlaunch.dualscreen;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only chat log for the bottom-screen deck. Newest lines sit at the bottom.
 * Parsing happens off the UI thread; {@link ControlDeckPresentation} hands in a list.
 * Does not steal focus — the utility-row Keyboard button already opens the system IME.
 */
public class ChatLogView extends ScrollView {
    private static final int MAX_LINES = 40;
    private static final int STICK_SLOP_PX = 12;

    private final LinearLayout mHost;
    private final List<ChatLine> mLines = new ArrayList<>();
    private long mAppliedSeq = -1;
    private boolean mPinnedToBottom = true;

    public static final class ChatLine {
        public String from;
        public String text;
        public String kind;
        public ChatLine(String from, String text, String kind) {
            this.from = from;
            this.text = text;
            this.kind = kind;
        }
    }

    public ChatLogView(Context c) { this(c, null); }
    public ChatLogView(Context c, AttributeSet a) {
        super(c, a);
        setFillViewport(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setBackgroundColor(0xFF1E1E1E);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);

        mHost = new LinearLayout(c);
        mHost.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        mHost.setPadding(pad, pad, pad, pad);
        mHost.setFocusable(false);
        addView(mHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        showPlaceholder();
    }

    /** Replace the visible lines. Auto-scrolls to bottom unless the user has scrolled up. */
    public void setLines(List<ChatLine> lines, long seq) {
        if (seq == mAppliedSeq) return;
        boolean stick = mPinnedToBottom;
        mAppliedSeq = seq;
        mLines.clear();
        if (lines != null) {
            int start = Math.max(0, lines.size() - MAX_LINES);
            for (int i = start; i < lines.size(); i++) mLines.add(lines.get(i));
        }
        rebuild();
        if (stick) post(this::scrollToLatest);
    }

    public void scrollToLatest() {
        mPinnedToBottom = true;
        fullScroll(FOCUS_DOWN);
        post(() -> fullScroll(FOCUS_DOWN));
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        View child = getChildAt(0);
        if (child == null) return;
        int range = child.getHeight() - getHeight() + getPaddingBottom();
        boolean atBottom = t >= range - STICK_SLOP_PX;
        if (!atBottom && t != oldt) {
            mPinnedToBottom = false;
        } else if (atBottom) {
            mPinnedToBottom = true;
        }
    }

    private void rebuild() {
        mHost.removeAllViews();
        if (mLines.isEmpty()) {
            showPlaceholder();
            return;
        }
        Context ctx = getContext();
        float size = 12f;
        for (int i = 0; i < mLines.size(); i++) {
            ChatLine line = mLines.get(i);
            TextView tv = new TextView(ctx);
            tv.setFocusable(false);
            tv.setTextIsSelectable(false);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            tv.setPadding(0, dp(1), 0, dp(1));
            boolean system = isSystem(line.kind);
            if (system) {
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.ITALIC);
                tv.setTextColor(0xFF9A9A9A);
                tv.setText(line.text == null ? "" : line.text);
            } else {
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
                tv.setTextColor(0xFFEAEAEA);
                String from = line.from == null ? "" : line.from;
                String text = line.text == null ? "" : line.text;
                tv.setText(from.isEmpty() ? text : ("<" + from + "> " + text));
            }
            mHost.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void showPlaceholder() {
        TextView tv = new TextView(getContext());
        tv.setFocusable(false);
        tv.setTextColor(0x99CCCCCC);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setText("Chat will appear here");
        mHost.addView(tv);
    }

    private static boolean isSystem(String kind) {
        if (kind == null) return false;
        return "system".equalsIgnoreCase(kind) || "sys".equalsIgnoreCase(kind)
                || "info".equalsIgnoreCase(kind);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
