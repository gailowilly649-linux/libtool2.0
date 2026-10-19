package imgui.il2cpp.tool;

import android.view.Surface;

public class NativeMethods {
    static {
        try {
            System.loadLibrary("Tool");
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
        }
    }

    public static native void onSurfaceCreated(Surface surface);
    public static native void onSurfaceCreate();
    public static native void onSurfaceChanged(int width, int height);
    public static native void onDrawFrame();
    public static native void onTouchEvent(int action, float x, int x_orig, float y, int y_orig);
    public static native String getWindowRect();
}
