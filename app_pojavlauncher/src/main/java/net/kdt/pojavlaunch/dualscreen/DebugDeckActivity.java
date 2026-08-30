package net.kdt.pojavlaunch.dualscreen;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Exported debug entry so emulator harden can attach {@link ControlDeckPresentation}
 * without booting a full Minecraft session into MainActivity (:game).
 * Temporary for Thor dual-screen proof; remove once in-game path is proven.
 */
public class DebugDeckActivity extends Activity {
    private static final String TAG = "DebugDeckActivity";
    private DualScreenManager mDualScreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Thor DebugDeck (primary)\nBottom Presentation should attach if overlay exists.");
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(Color.BLACK);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(16f);
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);
        mDualScreen = new DualScreenManager(this, new DualScreenManager.DeckCallback() {
            @Override public void onDeckAttached() {
                Log.i(TAG, "deck attached");
                tv.post(() -> tv.setText(tv.getText() + "\n\ndeck ATTACHED"));
            }
            @Override public void onDeckDetached() {
                Log.i(TAG, "deck detached");
            }
        });
        Log.i(TAG, "onCreate DualScreenManager ready hasSecondary=" + mDualScreen.hasSecondaryDisplay());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mDualScreen != null) mDualScreen.onResume();
    }

    @Override
    protected void onPause() {
        if (mDualScreen != null) mDualScreen.onPause();
        super.onPause();
    }
}
