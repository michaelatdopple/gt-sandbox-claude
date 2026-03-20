package org.libsdl.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.util.Log;

/**
 * ContextWrapper that adds SDLActivity-specific method stubs for fragment mode.
 *
 * Love2D's android.cpp calls JNI methods (getImmersiveMode, getDPIScale, etc.)
 * on the object returned by SDL_GetAndroidActivity(). Normally that's an
 * SDLActivity instance. In fragment mode it's our host Activity, which lacks
 * those methods. This wrapper provides them with sensible defaults.
 */
public class LoveFragmentContext extends ContextWrapper {

    public LoveFragmentContext(Context base) {
        super(base);
    }

    // --- Methods that love's android.cpp looks up via JNI ---

    public void setImmersiveMode(boolean immersive) {
        // No-op in fragment mode — the fragment manages its own window flags.
    }

    public boolean getImmersiveMode() {
        return true; // Fragment is fullscreen
    }

    public float getDPIScale() {
        // 1:1 pixel mapping — matches webview setInitialScale(100) pattern.
        // Device is 800x800 physical pixels at 320 DPI; Love2D should not
        // double the framebuffer.
        return 1.0f;
    }

    public Rect getSafeArea() {
        return new Rect(0, 0, 0, 0);
    }

    public void vibrate(double seconds) {
        // No-op
    }

    public boolean hasBackgroundMusic() {
        return false;
    }

    public boolean hasRecordAudioPermission() {
        return false;
    }

    public void requestRecordAudioPermission() {
        // No-op
    }

    public void showRecordingAudioPermissionMissingDialog() {
        // No-op
    }

    public String getCRequirePath() {
        return "";
    }

    public String[] buildFileTree() {
        return new String[0];
    }

    /**
     * SDL message box — just log the message and return 0 (first button).
     * In fragment mode we can't show an AlertDialog from a non-Activity context.
     */
    public int messageboxShowMessageBox(
            int flags, String title, String message,
            int[] buttonFlags, int[] buttonIds, String[] buttonTexts, int[] colors) {
        Log.e("LoveFragmentContext", "MessageBox [" + title + "]: " + message);
        return buttonIds != null && buttonIds.length > 0 ? buttonIds[0] : 0;
    }
}
