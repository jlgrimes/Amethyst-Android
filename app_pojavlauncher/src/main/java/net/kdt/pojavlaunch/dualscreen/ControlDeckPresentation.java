package net.kdt.pojavlaunch.dualscreen;

import android.app.Activity;
import android.app.Presentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

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
 */
public class ControlDeckPresentation extends Presentation {

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
    private static final int GOLD = 0xFFFFB000;
    private static final int TAB_DIM = 0xFF8A8A8A;

    private final Activity mActivity;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private ViewGroup mDeckRoot;
    private ControlLayout mDeckControlLayout;

    private MinimapView mMinimap;
    private InventoryView mInventory;
    private ChatLogView mChat;
    private StatusStripView mStatus;
    private View mTabMap, mTabInv, mTabChat;
    private TextView mTabMapLabel, mTabInvLabel, mTabChatLabel;
    private View mTabMapBar, mTabInvBar, mTabChatBar;
    private int mSelectedTab = TAB_MAP;

    private File mMapPng;
    private File mMapJson;
    private File mHudJson;
    private File mChatJson;

    private HandlerThread mIoThread;
    private Handler mIoHandler;
    private long mMapPngMod = -1;
    private long mMapJsonMod = -1;
    private long mHudMod = -1;
    private long mChatMod = -1;
    private long mMapSeq = -1;
    private long mHudSeq = -1;
    private long mChatSeq = -1;
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

        mMinimap = findViewById(R.id.deck_map);
        mInventory = findViewById(R.id.deck_inventory);
        mChat = findViewById(R.id.deck_chat);
        mStatus = findViewById(R.id.deck_status);
        mTabMap = findViewById(R.id.deck_tab_map);
        mTabInv = findViewById(R.id.deck_tab_inv);
        mTabChat = findViewById(R.id.deck_tab_chat);
        mTabMapLabel = findViewById(R.id.deck_tab_map_label);
        mTabInvLabel = findViewById(R.id.deck_tab_inv_label);
        mTabChatLabel = findViewById(R.id.deck_tab_chat_label);
        mTabMapBar = findViewById(R.id.deck_tab_map_bar);
        mTabInvBar = findViewById(R.id.deck_tab_inv_bar);
        mTabChatBar = findViewById(R.id.deck_tab_chat_bar);

        if (mTabMap != null) mTabMap.setOnClickListener(v -> selectTab(TAB_MAP));
        if (mTabInv != null) mTabInv.setOnClickListener(v -> selectTab(TAB_INV));
        if (mTabChat != null) mTabChat.setOnClickListener(v -> selectTab(TAB_CHAT));
        selectTab(TAB_MAP);

        File files = mActivity.getExternalFilesDir(null);
        if (files != null) {
            File deck = new File(files, ".minecraft/thor_deck");
            // Inventory + command.json stay on InventoryView's own HandlerThread (unchanged).
            if (mInventory != null) {
                mInventory.setInventoryFile(new File(deck, "inventory.json"));
                mInventory.setIconDir(new File(deck, "icons"));
                mInventory.setCommandFile(new File(deck, "command.json"));
            }
            mMapPng = new File(deck, "map.png");
            mMapJson = new File(deck, "map.json");
            mHudJson = new File(deck, "hud.json");
            mChatJson = new File(deck, "chat.json");
        }

        startPoller();
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
        if (mMinimap != null) mMinimap.setVisibility(tab == TAB_MAP ? View.VISIBLE : View.GONE);
        if (mInventory != null) mInventory.setVisibility(tab == TAB_INV ? View.VISIBLE : View.GONE);
        if (mChat != null) mChat.setVisibility(tab == TAB_CHAT ? View.VISIBLE : View.GONE);
        styleTab(mTabMapLabel, mTabMapBar, tab == TAB_MAP);
        styleTab(mTabInvLabel, mTabInvBar, tab == TAB_INV);
        styleTab(mTabChatLabel, mTabChatBar, tab == TAB_CHAT);
        if (tab == TAB_CHAT && mChat != null) mChat.scrollToLatest();
    }

    private static void styleTab(TextView label, View bar, boolean on) {
        if (label != null) label.setTextColor(on ? GOLD : TAB_DIM);
        if (bar != null) bar.setBackgroundColor(on ? GOLD : 0x00000000);
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
     * Inventory.json is owned by {@link InventoryView} so command.json tap-to-move stays intact.
     */
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            try {
                pollMapPng();
                pollMapJson();
                pollHud();
                pollChat();
            } catch (Exception ignored) {
            }
            Handler io = mIoHandler;
            if (io != null) io.postDelayed(this, POLL_MS);
        }
    };

    private void pollMapPng() {
        boolean exists = mMapPng != null && mMapPng.exists();
        if (!exists) {
            if (!mMapPngMissingAnnounced) {
                mMapPngMissingAnnounced = true;
                mMapPngMod = -1;
                final int gen = ++mMapGen;
                mUiHandler.post(() -> {
                    if (!isShowing() || gen != mMapGen || mMinimap == null) return;
                    mMinimap.setMapBitmap(null);
                });
            }
            return;
        }
        mMapPngMissingAnnounced = false;
        long mod = mMapPng.lastModified();
        if (mod == mMapPngMod) return;
        mMapPngMod = mod;
        final Bitmap bmp = MinimapView.decodeMapPng(mMapPng);
        final int gen = ++mMapGen;
        mUiHandler.post(() -> {
            if (!isShowing() || mMinimap == null || gen != mMapGen) {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                return;
            }
            mMinimap.setMapBitmap(bmp);
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
            if (!isShowing() || mMinimap == null) return;
            mMinimap.setMeta(meta);
        });
    }

    private void pollHud() {
        if (mHudJson == null || !mHudJson.exists()) return;
        long mod = mHudJson.lastModified();
        if (mod == mHudMod) return;
        mHudMod = mod;
        final StatusStripView.HudState hud = parseHud(readFileQuiet(mHudJson));
        if (hud == null) return;
        if (hud.seq > 0 && hud.seq <= mHudSeq) return;
        if (hud.seq > 0) mHudSeq = hud.seq;
        mUiHandler.post(() -> {
            if (!isShowing() || mStatus == null) return;
            mStatus.setHud(hud);
        });
    }

    private void pollChat() {
        if (mChatJson == null || !mChatJson.exists()) return;
        long mod = mChatJson.lastModified();
        if (mod == mChatMod) return;
        mChatMod = mod;
        ChatParse parsed = parseChat(readFileQuiet(mChatJson));
        if (parsed == null) return;
        if (parsed.seq > 0 && parsed.seq <= mChatSeq) return;
        if (parsed.seq > 0) mChatSeq = parsed.seq;
        final long seq = parsed.seq;
        final List<ChatLogView.ChatLine> lines = parsed.lines;
        mUiHandler.post(() -> {
            if (!isShowing() || mChat == null) return;
            mChat.setLines(lines, seq);
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
