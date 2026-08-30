package net.kdt.pojavlaunch.dualscreen;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import net.kdt.pojavlaunch.Tools;

/**
 * Exported debug entry so emulator harden can attach {@link ControlDeckPresentation}
 * without booting a full Minecraft session into MainActivity (:game).
 * Temporary for Thor dual-screen proof; remove once in-game path is proven.
 */
public class DebugDeckActivity extends Activity {
    private static final String TAG = "DebugDeckActivity";
    private DualScreenManager mDualScreen;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStatus = new TextView(this);
        mStatus.setText("Thor DebugDeck (primary)\nBottom Presentation should attach if overlay exists.");
        mStatus.setTextColor(Color.WHITE);
        mStatus.setBackgroundColor(Color.BLACK);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setTextSize(16f);
        mStatus.setPadding(48, 48, 48, 48);
        setContentView(mStatus);

        // ControlDeckPresentation → ControlData → Tools.dpToPx needs metrics.
        // Normal launcher path sets these before MainActivity; we must seed them here.
        Tools.getDisplayMetrics(this);
        Tools.updateWindowSize(this);

        mDualScreen = new DualScreenManager(this, new DualScreenManager.DeckCallback() {
            @Override public void onDeckAttached() {
                Log.i(TAG, "deck attached");
                mStatus.post(() -> mStatus.setText(
                        "Thor DebugDeck (primary)\ndeck ATTACHED\nhasSecondary="
                                + mDualScreen.hasSecondaryDisplay()));
            }
            @Override public void onDeckDetached() {
                Log.i(TAG, "deck detached");
            }
        });
        Log.i(TAG, "onCreate DualScreenManager ready hasSecondary="
                + mDualScreen.hasSecondaryDisplay()
                + " density=" + (Tools.currentDisplayMetrics != null
                ? Tools.currentDisplayMetrics.density : -1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Tools.currentDisplayMetrics == null) {
            Tools.getDisplayMetrics(this);
            Tools.updateWindowSize(this);
        }
        if (mDualScreen != null) {
            try {
                mDualScreen.onResume();
            } catch (Throwable t) {
                Log.e(TAG, "DualScreenManager.onResume failed", t);
                mStatus.setText("deck attach FAILED:\n" + t);
            }
        }
    }

    @Override
    protected void onPause() {
        if (mDualScreen != null) {
            try {
                mDualScreen.onPause();
            } catch (Throwable t) {
                Log.w(TAG, "onPause dismiss", t);
            }
        }
        super.onPause();
    }
}
