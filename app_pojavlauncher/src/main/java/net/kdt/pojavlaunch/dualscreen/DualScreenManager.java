package net.kdt.pojavlaunch.dualscreen;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

/**
 * Detects a secondary "presentation" display (e.g. the AYN Thor's bottom touch screen) and
 * hosts the in-game control deck on it via {@link ControlDeckPresentation}.
 *
 * Input is display-agnostic: control buttons drive the game through static CallbackBridge calls,
 * so the deck can live on any display while Minecraft renders full-screen on the primary one.
 *
 * Fully optional: when no secondary display is present, nothing happens and the launcher behaves
 * exactly as before (single-screen fallback).
 */
public class DualScreenManager implements DisplayManager.DisplayListener {
    private static final String TAG = "DualScreenManager";

    /** Lets the host activity react when the deck attaches/detaches (e.g. hide/show top controls). */
    public interface DeckCallback {
        void onDeckAttached();
        void onDeckDetached();
    }

    private final Activity mActivity;
    private final DisplayManager mDisplayManager;
    private final DeckCallback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private ControlDeckPresentation mPresentation;
    private boolean mRegistered = false;

    public DualScreenManager(Activity activity, DeckCallback callback) {
        mActivity = activity;
        mCallback = callback;
        mDisplayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** True if a usable secondary display exists right now. */
    public boolean hasSecondaryDisplay() {
        return findPresentationDisplay() != null;
    }

    /** Pick the best secondary display: prefer one flagged FLAG_PRESENTATION, never the default. */
    private Display findPresentationDisplay() {
        if (mDisplayManager == null) return null;
        Display[] presentation =
                mDisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display d : presentation) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY
                    && (d.getFlags() & Display.FLAG_PRESENTATION) != 0) {
                return d;
            }
        }
        // Fallback: any non-default display in the presentation category.
        for (Display d : presentation) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return null;
    }

    /** Call from the host activity's onResume(). */
    public void onResume() {
        if (mDisplayManager == null) return;
        if (!mRegistered) {
            mDisplayManager.registerDisplayListener(this, mHandler);
            mRegistered = true;
        }
        showDeck();
    }

    /** Call from the host activity's onPause(). */
    public void onPause() {
        if (mDisplayManager != null && mRegistered) {
            mDisplayManager.unregisterDisplayListener(this);
            mRegistered = false;
        }
        dismissDeck();
    }

    private void showDeck() {
        Display target = findPresentationDisplay();
        if (target == null) return; // single-screen fallback

        // Re-create if the target display changed underneath us.
        if (mPresentation != null
                && mPresentation.getDisplay().getDisplayId() != target.getDisplayId()) {
            dismissDeck();
        }
        if (mPresentation != null) return; // already showing on the right display

        try {
            mPresentation = new ControlDeckPresentation(mActivity, target);
            mPresentation.setOnDismissListener(dialog -> {
                mPresentation = null;
                if (mCallback != null) mCallback.onDeckDetached();
            });
            mPresentation.show();
            if (mCallback != null) mCallback.onDeckAttached();
            Log.i(TAG, "Control deck attached to display " + target.getDisplayId()
                    + " (" + target.getName() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show control deck", e);
            mPresentation = null;
        }
    }

    private void dismissDeck() {
        if (mPresentation == null) return;
        try {
            mPresentation.dismiss();
        } catch (Exception e) {
            Log.w(TAG, "Error dismissing control deck", e);
        }
        mPresentation = null;
        if (mCallback != null) mCallback.onDeckDetached();
    }

    @Override
    public void onDisplayAdded(int displayId) {
        showDeck();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (mPresentation != null
                && mPresentation.getDisplay().getDisplayId() == displayId) {
            dismissDeck();
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        // No-op for now; a resolution/rotation change on the deck display is handled on next resume.
    }
}
