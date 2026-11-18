package net.kdt.pojavlaunch.runelite;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Entry point used by the Android launcher instead of {@code net.runelite.client.RuneLite}.
 *
 * <p>Caciocavallo only provides a single fixed-size virtual screen and has no window manager:
 * Frame#setExtendedState(MAXIMIZED_BOTH) is ignored (Toolkit#isFrameStateSupported only accepts
 * NORMAL) and every window keeps whatever bounds the application gives it. RuneLite therefore
 * opens a packed, desktop-sized frame in the top-left corner of the virtual screen with its own
 * title bar and border. {@link WindowFitter} acts as a minimal window manager so the client
 * always fills the screen that is blitted onto the Android surface.</p>
 */
public final class AndroidRuneLite {
    public static final String ANDROID_PROPERTY = "runelite.android";

    private AndroidRuneLite() {
    }

    public static boolean isAndroid() {
        return Boolean.getBoolean(ANDROID_PROPERTY)
                || System.getProperty("os.version", "").startsWith("Android");
    }

    public static void main(String[] args) throws Throwable {
        System.setProperty(ANDROID_PROPERTY, "true");
        WindowFitter.install();
        RepaintPump.install();
        InputFocus.install();

        Method main = Class.forName("net.runelite.client.RuneLite").getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
