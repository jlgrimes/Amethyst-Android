package net.kdt.pojavlaunch.dualscreen;

import android.app.Activity;
import android.app.Presentation;
import android.os.Bundle;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.CustomControls;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Presentation} shown on the secondary display (AYN Thor bottom touch screen) that hosts
 * a curated utility control deck. Movement / look / interaction are intentionally NOT included:
 * those stay on the device's physical controls. This deck only surfaces the auxiliary "overlay"
 * buttons (menu, keyboard, chat, inventory, perspective, debug, mouse toggle, escape) so they no
 * longer clutter the game view on the primary screen.
 *
 * Buttons drive the game through the shared static CallbackBridge, so they work from this display
 * while Minecraft renders full-screen on the primary one.
 *
 * Note: the "Keyboard" button triggers the real system IME, which the AYN Thor pins to the bottom
 * screen via its firmware setting (ime_show_on_second) — no custom keyboard is needed.
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

    private final Activity mActivity;
    private ControlLayout mDeckControlLayout;

    public ControlDeckPresentation(Activity activity, Display display) {
        super(activity, display);
        mActivity = activity;
    }

    public ControlLayout getDeckControlLayout() {
        return mDeckControlLayout;
    }

    /** Root container of the deck — used as a parent to slide side dialogs (settings) onto this screen. */
    public ViewGroup getDeckRoot() {
        return mDeckControlLayout;
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
        mDeckControlLayout = findViewById(R.id.deck_control_layout);
        mDeckControlLayout.setModifiable(false);

        // Route the "Menu" button to the host activity's in-game menu drawer.
        if (mActivity instanceof ControlButtonMenuListener) {
            mDeckControlLayout.setMenuListener((ControlButtonMenuListener) mActivity);
        }

        mDeckControlLayout.loadLayout(buildUtilityDeck());
        mDeckControlLayout.setControlVisible(true);

        // Inventory panel: reads the JSON file written by the companion Fabric mod (thor_deck mod)
        // and renders the player's inventory on this screen. Default-gameDir path (.minecraft).
        InventoryView inventory = findViewById(R.id.deck_inventory);
        if (inventory != null) {
            java.io.File files = mActivity.getExternalFilesDir(null);
            if (files != null) {
                java.io.File deck = new java.io.File(files, ".minecraft/thor_deck");
                inventory.setInventoryFile(new java.io.File(deck, "inventory.json"));
                inventory.setIconDir(new java.io.File(deck, "icons"));
                inventory.setCommandFile(new java.io.File(deck, "command.json"));
            }
        }
    }

    /** Build the curated utility layout (no movement controls). */
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
