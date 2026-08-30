package net.kdt.pojavlaunch.dualscreen;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;

/**
 * Detects a secondary "presentation" display (e.g. the AYN Thor's bottom touch screen) and
 * hosts the in-game control deck on it via {@link ControlDeckPresentation}.
 *
 * Input is display-agnostic: control buttons drive the game through static CallbackBridge calls,
 * so the deck can live on any display while Minecraft renders full-screen on the primary one.
 *
 * Fully optional: when no secondary display is present, nothing happens and the launcher behaves
 * exactly as before (single-screen fallback). Display add/remove is tracked via DisplayListener.
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

    /** Root view of the deck if it's currently showing, for hosting side dialogs on the deck screen. */
    public ViewGroup getDeckRoot() {
        return mPresentation != null ? mPresentation.getDeckRoot() : null;
    }

    /** Currently showing Presentation, or null. Used by DebugDeck DUMP_FRAME PixelCopy. */
    public ControlDeckPresentation getPresentation() {
        return mPresentation;
    }

    /** Overlay display id of the attached deck, or -1. */
    public int getAttachedDisplayId() {
        try {
            return mPresentation != null ? mPresentation.getDisplay().getDisplayId() : -1;
        } catch (Exception e) {
            return -1;
        }
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

    /** Call from the host activity's onPause(). Unregisters the listener, then dismisses. */
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
                // Only notify if we did not already clear this from dismissDeck()
                // (avoids double onDeckDetached when we dismiss ourselves).
                if (mPresentation != null) {
                    mPresentation = null;
                    notifyDetached();
                }
            });
            mPresentation.show();
            notifyAttached();
            Log.i(TAG, "Control deck attached to display " + target.getDisplayId()
                    + " (" + target.getName() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show control deck", e);
            mPresentation = null;
        }
    }

    private void dismissDeck() {
        if (mPresentation == null) return;
        ControlDeckPresentation presentation = mPresentation;
        mPresentation = null; // so the dismiss listener does not double-notify
        try {
            presentation.dismiss();
        } catch (Exception e) {
            Log.w(TAG, "Error dismissing control deck", e);
        }
        notifyDetached();
    }

    private void notifyAttached() {
        if (mCallback != null) mCallback.onDeckAttached();
    }

    private void notifyDetached() {
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
