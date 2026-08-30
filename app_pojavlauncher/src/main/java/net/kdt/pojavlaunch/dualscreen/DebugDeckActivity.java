package net.kdt.pojavlaunch.dualscreen;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Exported debug entry so emulator harden can attach {@link ControlDeckPresentation}
 * without booting a full Minecraft session into MainActivity (:game).
 * Temporary for Thor dual-screen proof; remove once in-game path is proven.
 *
 * DUMP_FRAME: PixelCopy the FLAG_NOT_FOCUSABLE Presentation (adb screencap of overlay is 0-byte).
 *   adb shell am broadcast -a net.kdt.pojavlaunch.dualscreen.DUMP_FRAME --es name harden-dump
 */
public class DebugDeckActivity extends Activity {
    private static final String TAG = "DebugDeckActivity";
    public static final String ACTION_DUMP_FRAME = "net.kdt.pojavlaunch.dualscreen.DUMP_FRAME";
    private DualScreenManager mDualScreen;
    private TextView mStatus;
    private BroadcastReceiver mDumpRx;
    private HandlerThread mCopyThread;
    private Handler mCopyHandler;

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
                                + mDualScreen.hasSecondaryDisplay()
                                + "\ndisplay=" + mDualScreen.getAttachedDisplayId()));
            }
            @Override public void onDeckDetached() {
                Log.i(TAG, "deck detached");
            }
        });
        registerDumpRx();
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

    @Override
    protected void onDestroy() {
        unregisterDumpRx();
        if (mCopyThread != null) {
            mCopyThread.quitSafely();
            mCopyThread = null;
            mCopyHandler = null;
        }
        super.onDestroy();
    }

    private void registerDumpRx() {
        if (mDumpRx != null) return;
        mDumpRx = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (!ACTION_DUMP_FRAME.equals(intent.getAction())) return;
                String name = intent.getStringExtra("name");
                if (name == null || name.isEmpty()) name = "harden-dump";
                dumpFrame(name);
            }
        };
        IntentFilter f = new IntentFilter(ACTION_DUMP_FRAME);
        registerReceiver(mDumpRx, f, Context.RECEIVER_EXPORTED);
        Log.i(TAG, "DUMP_FRAME receiver registered");
    }

    private void unregisterDumpRx() {
        if (mDumpRx == null) return;
        try { unregisterReceiver(mDumpRx); } catch (Exception ignored) {}
        mDumpRx = null;
    }

    private Handler copyHandler() {
        if (mCopyHandler == null) {
            mCopyThread = new HandlerThread("thor-pixelcopy");
            mCopyThread.start();
            mCopyHandler = new Handler(mCopyThread.getLooper());
        }
        return mCopyHandler;
    }

    private void dumpFrame(String name) {
        Window window = null;
        String src = "activity";
        int displayId = 0;
        ControlDeckPresentation pres = mDualScreen != null ? mDualScreen.getPresentation() : null;
        if (pres != null && pres.isShowing() && pres.getWindow() != null) {
            window = pres.getWindow();
            src = "presentation";
            try { displayId = pres.getDisplay().getDisplayId(); } catch (Exception ignored) {}
        } else if (getWindow() != null) {
            window = getWindow();
            src = "activity";
            try { displayId = getDisplay() != null ? getDisplay().getDisplayId() : 0; } catch (Exception ignored) {}
        }
        if (window == null) {
            Log.e(TAG, "dumpFrame FAIL no window name=" + name);
            return;
        }
        View decor = window.getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) {
            Log.e(TAG, "dumpFrame FAIL size " + w + "x" + h + " name=" + name + " src=" + src);
            return;
        }
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final String outName = name;
        final String source = src;
        final int disp = displayId;
        final Window win = window;
        try {
            PixelCopy.request(win, new Rect(0, 0, w, h), bmp, copyResult -> {
                if (copyResult != PixelCopy.SUCCESS) {
                    Log.e(TAG, "dumpFrame PixelCopy fail code=" + copyResult
                            + " name=" + outName + " src=" + source
                            + " display=" + disp + " " + w + "x" + h);
                    bmp.recycle();
                    return;
                }
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File out = new File(dir, outName + ".png");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.flush();
                    Log.i(TAG, "dumpFrame OK src=" + source + " display=" + disp
                            + " " + w + "x" + h + " -> " + out.getAbsolutePath()
                            + " bytes=" + out.length());
                } catch (Exception e) {
                    Log.e(TAG, "dumpFrame write fail", e);
                } finally {
                    bmp.recycle();
                }
            }, copyHandler());
        } catch (Exception e) {
            Log.e(TAG, "dumpFrame PixelCopy request fail", e);
            bmp.recycle();
        }
    }
}
