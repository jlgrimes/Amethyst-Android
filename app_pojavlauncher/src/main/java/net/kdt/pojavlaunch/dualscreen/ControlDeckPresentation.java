package net.kdt.pojavlaunch.dualscreen;

import android.app.Activity;
import android.app.Presentation;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.CustomControls;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Presentation} shown on the secondary display (AYN Thor bottom touch screen) that hosts
 * a curated utility control deck plus a 3DS-style second brain: status strip, live minimap (default),
 * inventory, and a read-only chat log.
 *
 * Movement / look / interaction are intentionally NOT included: those stay on the device's physical
 * controls. This deck only surfaces the auxiliary overlay buttons (menu, keyboard, chat, inventory,
 * perspective, debug, mouse toggle, escape) so they no longer clutter the game view.
 *
 * Buttons drive the game through the shared static CallbackBridge, so they work from this display
 * while Minecraft renders full-screen on the primary one.
 *
 * Note: the "Keyboard" button triggers the real system IME, which the AYN Thor pins to the bottom
 * screen via its firmware setting (ime_show_on_second) — no custom keyboard is needed. Chat on this
 * deck is read-only and never takes focus ({@code FLAG_NOT_FOCUSABLE}).
 *
 * Live MAP is drawn by {@link SkinDeckView} into the leather hole. INV/CHAT overlays sit in
 * {@link SkinHoles} scaled into the letterboxed dest. File IPC is the existing bus:
 * {@code state.json} (seq) + {@code map.png} + {@code icons/} + {@code command.json}, with
 * split-file fallback ({@code inventory.json}/{@code chat.json}/{@code map.json}/{@code hud.json}).
 */
public class ControlDeckPresentation extends Presentation {

    private static final String TAG = "ControlDeckPresentation";

    // Grid column X expressions (4 columns), evaluated against the deck's geometry.
    private static final String COL_1 = "${margin}";
    private static final String COL_2 = "${margin} * 2 + ${width}";
    private static final String COL_3 = "${margin} * 3 + ${width} * 2";
    private static final String COL_4 = "${margin} * 4 + ${width} * 3";
    // Grid row Y expressions (2 rows).
    private static final String ROW_1 = "${margin}";
    private static final String ROW_2 = "${margin} * 2 + ${height}";

    private static final long POLL_MS = 200;
    private static final int TAB_MAP = 0;
    private static final int TAB_INV = 1;
    private static final int TAB_CHAT = 2;
    private final Activity mActivity;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private ViewGroup mDeckRoot;
    private ControlLayout mDeckControlLayout;

    private SkinDeckView mSkin;
    private View mContent;
    private MinimapView mMinimap;
    private InventoryView mInventory;
    private ChatLogView mChat;
    private StatusStripView mStatus;
    private final RectF mSkinDest = new RectF();
    private int mSelectedTab = TAB_MAP;
    private int mLastDestW, mLastDestH;

    private File mDeckDir;
    private File mStateJson;
    private File mMapPng;
    private File mMapJson;
    private File mHudJson;
    private File mChatJson;
    private File mInvJson;
    private File mIconDir;
    private File mCommandJson;

    private HandlerThread mIoThread;
    private Handler mIoHandler;
    private volatile long mMapPngMod = -1;
    private volatile boolean mMapApplyPending;
    private long mMapJsonMod = -1;
    private long mHudMod = -1;
    private long mChatMod = -1;
    private long mInvMod = -1;
    private long mStateMod = -1;
    private long mMapSeq = -1;
    private long mHudSeq = -1;
    private long mChatSeq = -1;
    private long mStateSeq = -1;
    private boolean mMapPngMissingAnnounced;
    private volatile int mMapGen;

    public ControlDeckPresentation(Activity activity, Display display) {
        super(activity, display);
        mActivity = activity;
    }

    public ControlLayout getDeckControlLayout() {
        return mDeckControlLayout;
    }

    /** Root container of the deck — used as a parent to slide side dialogs (settings) onto this screen. */
    public ViewGroup getDeckRoot() {
        return mDeckRoot != null ? mDeckRoot : mDeckControlLayout;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getWindow() != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // Don't steal input focus from the game on the primary display.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().setBackgroundDrawable(new ColorDrawable(0xFF1A140E));
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.alpha = 1f;
            lp.dimAmount = 0f;
            getWindow().setAttributes(lp);
        }

        setContentView(R.layout.presentation_control_deck);
        mDeckRoot = findViewById(R.id.deck_root);
        mDeckControlLayout = findViewById(R.id.deck_control_layout);
        mDeckControlLayout.setModifiable(false);

        // Route the "Menu" button to the host activity's in-game menu drawer.
        if (mActivity instanceof ControlButtonMenuListener) {
            mDeckControlLayout.setMenuListener((ControlButtonMenuListener) mActivity);
        }

        mDeckControlLayout.loadLayout(buildUtilityDeck());
        mDeckControlLayout.setControlVisible(true);

        mSkin = findViewById(R.id.deck_skin);
        mContent = findViewById(R.id.deck_content);
        mMinimap = findViewById(R.id.deck_map);
        mInventory = findViewById(R.id.deck_inventory);
        mChat = findViewById(R.id.deck_chat);
        mStatus = findViewById(R.id.deck_status);
        if (mMinimap != null) {
            mMinimap.setHoleMode(true);
            mMinimap.setVisibility(View.GONE); // MAP punch is SkinDeckView (harness-proven)
        }
        if (mInventory != null) {
            mInventory.setHoleMode(true);
            mInventory.setLiveFeed(true);
        }
        if (mSkin != null) {
            mSkin.setListener(this::selectTab);
            mSkin.setLayoutListener(dest -> layoutOverlays());
        }
        if (mDeckRoot != null) {
            mDeckRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> layoutOverlays());
        }
        resolveDeckDir();
        if (mInventory != null) {
            mInventory.setInventoryFile(mInvJson);
            mInventory.setIconDir(mIconDir);
            mInventory.setCommandFile(mCommandJson);
        }
        selectTab(TAB_MAP);
        layoutOverlays();

        startPoller();
        Log.i(TAG, "onCreate display=" + getDisplay().getDisplayId()
                + " FLAG_NOT_FOCUSABLE+FLAG_KEEP_SCREEN_ON"
                + " deck=" + (mDeckDir == null ? "null" : mDeckDir.getAbsolutePath()));
    }

    /**
     * Existing file bus. Prefer a dir that already has state.json / map.png / inventory.json:
     * {@code .minecraft/thor_deck} (Pojav gameDir), then {@code thor_deck/}, then {@code deck/}.
     */
    private void resolveDeckDir() {
        File files = mActivity.getExternalFilesDir(null);
        if (files == null || !files.exists()) files = mActivity.getFilesDir();
        if (files == null) return;
        File[] candidates = new File[] {
                new File(files, ".minecraft/thor_deck"),
                new File(files, "thor_deck"),
                new File(files, "deck"),
        };
        File chosen = candidates[0];
        for (int i = 0; i < candidates.length; i++) {
            File c = candidates[i];
            if (new File(c, "state.json").exists() || new File(c, "map.png").exists()
                    || new File(c, "inventory.json").exists()) {
                chosen = c;
                break;
            }
        }
        mDeckDir = chosen;
        //noinspection ResultOfMethodCallIgnored
        mDeckDir.mkdirs();
        File icons = new File(mDeckDir, "icons");
        //noinspection ResultOfMethodCallIgnored
        icons.mkdirs();
        mStateJson = new File(mDeckDir, "state.json");
        mMapPng = new File(mDeckDir, "map.png");
        mMapJson = new File(mDeckDir, "map.json");
        mHudJson = new File(mDeckDir, "hud.json");
        mChatJson = new File(mDeckDir, "chat.json");
        mInvJson = new File(mDeckDir, "inventory.json");
        mIconDir = icons;
        mCommandJson = new File(mDeckDir, "command.json");
    }

    @Override
    protected void onStop() {
        stopPoller();
        super.onStop();
    }

    @Override
    public void dismiss() {
        stopPoller();
        super.dismiss();
    }

    private void selectTab(int tab) {
        mSelectedTab = tab;
        if (mSkin != null) mSkin.setTab(tab);
        if (mMinimap != null) mMinimap.setVisibility(View.GONE);
        if (mInventory != null) mInventory.setVisibility(tab == TAB_INV ? View.VISIBLE : View.GONE);
        if (mChat != null) {
            mChat.setVisibility(tab == TAB_CHAT ? View.VISIBLE : View.GONE);
            if (tab == TAB_CHAT) mChat.scrollToLatest();
        }
        if (mStatus != null) mStatus.setVisibility(View.VISIBLE);
        Log.i(TAG, "selectTab " + tab);
    }

    /**
     * Place live overlays from measured mock rects ({@link SkinHoles}) scaled into the
     * letterboxed skin dest. Not leftover 480×800 view percentages.
     */
    private void layoutOverlays() {
        if (mDeckRoot == null || mSkin == null) return;
        int w = mDeckRoot.getWidth();
        int h = mDeckRoot.getHeight();
        if (w <= 0 || h <= 0) return;

        mSkin.getSkinDest(mSkinDest);
        if (mSkinDest.isEmpty()) return;
        RectF dest = new RectF(mSkinDest);
        dest.offset(mSkin.getLeft(), mSkin.getTop());

        // Grouping layer fills the root so SkinHoles.place children use dest coords.
        if (mContent != null && mContent.getParent() instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mContent.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            lp.rightMargin = 0;
            lp.bottomMargin = 0;
            mContent.setLayoutParams(lp);
        }

        SkinHoles.place(mMinimap, dest, SkinHoles.MAP);
        SkinHoles.place(mInventory, dest, SkinHoles.INV);
        SkinHoles.place(mChat, dest, SkinHoles.CHAT);
        SkinHoles.place(mStatus, dest, SkinHoles.HUD);
        if (mStatus != null) mStatus.setVisibility(View.VISIBLE);

        // Utility row sits in the dest band above the HUD hole (utility ends ~y120).
        float sl = dest.left, st = dest.top, sw = dest.width(), sh = dest.height();
        if (sw <= 0f || sh <= 0f) return;
        if (mDeckControlLayout != null) {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) mDeckControlLayout.getLayoutParams();
            lp.height = Math.round(sh * (120f / SkinHoles.SKIN_H));
            lp.topMargin = Math.round(st);
            lp.leftMargin = Math.round(sl);
            lp.rightMargin = Math.round(w - (sl + sw));
            mDeckControlLayout.setLayoutParams(lp);
        }

        int dw = Math.round(dest.width()), dh = Math.round(dest.height());
        if (dw != mLastDestW || dh != mLastDestH) {
            mLastDestW = dw;
            mLastDestH = dh;
            Log.i(TAG, "holes dest=" + dw + "x" + dh
                    + " map=" + SkinHoles.scale(dest, SkinHoles.MAP).toShortString()
                    + " inv=" + SkinHoles.scale(dest, SkinHoles.INV).toShortString()
                    + " chat=" + SkinHoles.scale(dest, SkinHoles.CHAT).toShortString()
                    + " hud=" + SkinHoles.scale(dest, SkinHoles.HUD).toShortString());
        }
    }

    private void startPoller() {
        if (mIoThread != null) return;
        mIoThread = new HandlerThread("thor-deck-io");
        mIoThread.start();
        mIoHandler = new Handler(mIoThread.getLooper());
        mIoHandler.post(mPoll);
    }

    private void stopPoller() {
        if (mIoHandler != null) {
            mIoHandler.removeCallbacksAndMessages(null);
            mIoHandler = null;
        }
        if (mIoThread != null) {
            mIoThread.quitSafely();
            mIoThread = null;
        }
        mUiHandler.removeCallbacksAndMessages(null);
    }

    /**
     * File bus poller. All reads + JSON/PNG parse happen on this HandlerThread.
     * {@code state.json} is the primary seq bus; split files are fallback.
     * {@code command.json} tap-to-move stays on {@link InventoryView}.
     */
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            try {
                pollState();
                pollMapPng();
                pollMapJson();
                pollHud();
                pollChatFallback();
                pollInvFallback();
            } catch (Exception ignored) {
            }
            Handler io = mIoHandler;
            if (io != null) io.postDelayed(this, POLL_MS);
        }
    };

    private void pollState() {
        if (mStateJson == null || !mStateJson.exists()) return;
        long mod = mStateJson.lastModified();
        if (mod == mStateMod) return;
        String raw = readFileQuiet(mStateJson);
        if (raw == null || raw.isEmpty()) return;
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (Exception e) {
            return;
        }
        long seq = o.optLong("seq", 0);
        if (seq > 0 && seq <= mStateSeq) return;
        mStateMod = mod;
        if (seq > 0) mStateSeq = seq;
        Log.i(TAG, "state.json path=" + mStateJson.getAbsolutePath() + " seq=" + seq + " mod=" + mod);

        JSONObject map = o.optJSONObject("map");
        if (map != null) {
            final MinimapView.MapMeta meta = parseMap(map.toString());
            if (meta != null) {
                if (meta.seq > 0) mMapSeq = meta.seq;
                mUiHandler.post(() -> {
                    if (!isShowing()) return;
                    if (mSkin != null) mSkin.setMeta(meta);
                    if (mMinimap != null) mMinimap.setMeta(meta);
                });
            }
        }

        JSONObject inv = o.optJSONObject("inventory");
        if (inv == null && o.has("slots")) inv = o;
        if (inv != null) {
            final String invJson = inv.toString();
            mUiHandler.post(() -> {
                if (!isShowing() || mInventory == null) return;
                mInventory.applyJson(invJson);
            });
        }

        JSONObject hudObj = o.optJSONObject("hud");
        if (hudObj != null) {
            final StatusStripView.HudState hud = parseHud(hudObj.toString());
            if (hud != null) {
                if (hud.seq > 0) mHudSeq = hud.seq;
                mUiHandler.post(() -> {
                    if (!isShowing() || mStatus == null) return;
                    mStatus.setHud(hud);
                });
            }
        }

        JSONObject chat = o.optJSONObject("chat");
        JSONArray linesArr = chat != null ? chat.optJSONArray("lines") : o.optJSONArray("lines");
        long chatSeq = chat != null ? chat.optLong("seq", seq) : seq;
        if (linesArr != null) {
            ChatParse parsed = new ChatParse();
            parsed.seq = chatSeq;
            parsed.lines = new ArrayList<>();
            for (int i = 0; i < linesArr.length(); i++) {
                JSONObject line = linesArr.optJSONObject(i);
                if (line == null) continue;
                parsed.lines.add(new ChatLogView.ChatLine(
                        line.optString("from", ""),
                        line.optString("text", ""),
                        line.optString("kind", "chat")));
            }
            if (parsed.seq > 0) mChatSeq = parsed.seq;
            final long applySeq = parsed.seq;
            final List<ChatLogView.ChatLine> lines = parsed.lines;
            mUiHandler.post(() -> {
                if (!isShowing() || mChat == null) return;
                mChat.setLines(lines, applySeq);
            });
        }
    }

    private void pollMapPng() {
        boolean exists = mMapPng != null && mMapPng.exists();
        if (!exists) {
            if (!mMapPngMissingAnnounced) {
                mMapPngMissingAnnounced = true;
                mMapPngMod = -1;
                final int gen = ++mMapGen;
                mUiHandler.post(() -> {
                    if (!isShowing() || gen != mMapGen) return;
                    if (mSkin != null) mSkin.setLiveMap(null);
                    if (mMinimap != null) mMinimap.setMapBitmap(null);
                });
            }
            return;
        }
        mMapPngMissingAnnounced = false;
        long mod = mMapPng.lastModified();
        if (mod == mMapPngMod) return;
        if (mMapApplyPending) return;
        final Bitmap bmp = MinimapView.decodeMapPng(mMapPng);
        if (bmp == null) return;
        mMapApplyPending = true;
        final int gen = ++mMapGen;
        mUiHandler.post(() -> {
            try {
                if (!isShowing() || mSkin == null || gen != mMapGen) {
                    if (!bmp.isRecycled()) bmp.recycle();
                    Log.w(TAG, "map.png apply dropped — will retry, not latching mMapPngMod");
                    return;
                }
                mSkin.setLiveMap(bmp);
                mMapPngMod = mod;
                Log.i(TAG, "map.png applied " + bmp.getWidth() + "x" + bmp.getHeight()
                        + " path=" + mMapPng.getAbsolutePath() + " mod=" + mod);
            } finally {
                mMapApplyPending = false;
            }
        });
    }

    private void pollMapJson() {
        if (mMapJson == null || !mMapJson.exists()) return;
        long mod = mMapJson.lastModified();
        if (mod == mMapJsonMod) return;
        mMapJsonMod = mod;
        final MinimapView.MapMeta meta = parseMap(readFileQuiet(mMapJson));
        if (meta == null) return;
        if (meta.seq > 0 && meta.seq <= mMapSeq) return;
        if (meta.seq > 0) mMapSeq = meta.seq;
        mUiHandler.post(() -> {
            if (!isShowing()) return;
            if (mSkin != null) mSkin.setMeta(meta);
            if (mMinimap != null) mMinimap.setMeta(meta);
        });
    }

    private void pollHud() {
        if (mHudJson == null || !mHudJson.exists()) return;
        long mod = mHudJson.lastModified();
        if (mod == mHudMod) return;
        final StatusStripView.HudState hud = parseHud(readFileQuiet(mHudJson));
        if (hud == null) return;
        if (hud.seq > 0 && hud.seq <= mHudSeq) return;
        mHudMod = mod;
        if (hud.seq > 0) mHudSeq = hud.seq;
        Log.i(TAG, "hud.json seq=" + hud.seq + " hp=" + hud.hp + " hunger=" + hud.hunger
                + " x=" + (int) hud.x + " z=" + (int) hud.z);
        mUiHandler.post(() -> {
            if (!isShowing() || mStatus == null) {
                mHudMod = -1;
                return;
            }
            mStatus.setHud(hud);
        });
    }

    private void pollChatFallback() {
        if (mStateJson != null && mStateJson.exists()) return; // state.json owns chat
        if (mChatJson == null || !mChatJson.exists()) return;
        long mod = mChatJson.lastModified();
        if (mod == mChatMod) return;
        ChatParse parsed = parseChat(readFileQuiet(mChatJson));
        if (parsed == null) return;
        if (parsed.seq > 0 && parsed.seq <= mChatSeq) return;
        mChatMod = mod;
        if (parsed.seq > 0) mChatSeq = parsed.seq;
        final long seq = parsed.seq;
        final List<ChatLogView.ChatLine> lines = parsed.lines;
        mUiHandler.post(() -> {
            if (!isShowing() || mChat == null) {
                mChatMod = -1;
                mChatSeq = -1;
                return;
            }
            mChat.setLines(lines, seq);
        });
    }

    private void pollInvFallback() {
        if (mStateJson != null && mStateJson.exists()) return;
        if (mInvJson == null || !mInvJson.exists()) return;
        long mod = mInvJson.lastModified();
        if (mod == mInvMod) return;
        mInvMod = mod;
        final String json = readFileQuiet(mInvJson);
        if (json == null) return;
        mUiHandler.post(() -> {
            if (!isShowing() || mInventory == null) return;
            mInventory.applyJson(json);
        });
    }

    private static MinimapView.MapMeta parseMap(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            MinimapView.MapMeta m = new MinimapView.MapMeta();
            m.seq = o.optLong("seq", 0);
            m.x = o.optDouble("x", 0);
            m.y = o.optDouble("y", 0);
            m.z = o.optDouble("z", 0);
            m.yaw = (float) o.optDouble("yaw", 0);
            m.dim = o.optString("dim", "");
            m.biome = o.optString("biome", "");
            m.w = o.optInt("w", 128);
            m.h = o.optInt("h", 128);
            m.scale = o.optInt("scale", 1);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static StatusStripView.HudState parseHud(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            StatusStripView.HudState h = new StatusStripView.HudState();
            h.seq = o.optLong("seq", 0);
            h.hp = (float) o.optDouble("hp", 20);
            h.maxHp = (float) o.optDouble("maxHp", 20);
            h.hunger = o.optInt("hunger", 20);
            h.saturation = (float) o.optDouble("saturation", 0);
            h.air = o.optInt("air", 0);
            h.xp = (float) o.optDouble("xp", 0);
            h.level = o.optInt("level", 0);
            h.armor = o.optInt("armor", 0);
            h.x = o.optDouble("x", 0);
            h.y = o.optDouble("y", 0);
            h.z = o.optDouble("z", 0);
            h.yaw = (float) o.optDouble("yaw", 0);
            h.pitch = (float) o.optDouble("pitch", 0);
            h.biome = o.optString("biome", "");
            h.dim = o.optString("dim", "");
            h.time = o.optLong("time", 0);
            h.dayTime = o.optLong("dayTime", 0);
            h.weather = o.optString("weather", "");
            return h;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class ChatParse {
        long seq;
        List<ChatLogView.ChatLine> lines;
    }

    private static ChatParse parseChat(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            ChatParse p = new ChatParse();
            p.seq = o.optLong("seq", 0);
            p.lines = new ArrayList<>();
            JSONArray arr = o.optJSONArray("lines");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject line = arr.optJSONObject(i);
                    if (line == null) continue;
                    p.lines.add(new ChatLogView.ChatLine(
                            line.optString("from", ""),
                            line.optString("text", ""),
                            line.optString("kind", "chat")));
                }
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFileQuiet(File f) {
        try {
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
        } catch (Exception e) {
            return null;
        }
    }

    /** Build the curated utility layout (no movement / look / A-B controls). */
    private CustomControls buildUtilityDeck() {
        List<ControlData> buttons = new ArrayList<>();
        // Row 1
        buttons.add(button("Menu", ControlData.SPECIALBTN_MENU, COL_1, ROW_1));
        buttons.add(button("Esc", LwjglGlfwKeycode.GLFW_KEY_ESCAPE, COL_2, ROW_1));
        buttons.add(button("Keyboard", ControlData.SPECIALBTN_KEYBOARD, COL_3, ROW_1));
        buttons.add(button("Mouse", ControlData.SPECIALBTN_VIRTUALMOUSE, COL_4, ROW_1));
        // Row 2
        buttons.add(button("Inventory", LwjglGlfwKeycode.GLFW_KEY_E, COL_1, ROW_2));
        buttons.add(button("Chat", LwjglGlfwKeycode.GLFW_KEY_T, COL_2, ROW_2));
        buttons.add(button("Perspective", LwjglGlfwKeycode.GLFW_KEY_F5, COL_3, ROW_2));
        buttons.add(button("Debug", LwjglGlfwKeycode.GLFW_KEY_F3, COL_4, ROW_2));

        CustomControls deck = new CustomControls();
        deck.mControlDataList.addAll(buttons);
        return deck;
    }

    private ControlData button(String name, int keycode, String dynamicX, String dynamicY) {
        ControlData data = new ControlData(name, new int[]{keycode}, dynamicX, dynamicY, false);
        // Utility buttons should always be available, both in menus and in-game.
        data.displayInGame = true;
        data.displayInMenu = true;
        return data;
    }
}
