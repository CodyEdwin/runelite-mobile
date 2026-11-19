package com.github.caciocavallosilano.cacio.ctc;

import java.awt.Desktop.Action;
import java.awt.desktop.AboutHandler;
import java.awt.desktop.QuitHandler;
import java.awt.peer.DesktopPeer;
import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * Replacement for the CTCDesktopPeer shipped in cacio-tta-1.18-SNAPSHOT.jar.
 *
 * The original reported BROWSE/OPEN as unsupported and its browse()/open() were empty, so
 * java.awt.Desktop could never open a link on Android with Java 17 (the Java 8 cacio build did
 * forward them to the launcher through libpojavexec_awt). This version forwards both to the
 * launcher's MainActivity#openLink via the natives in runelite/src/main/jni/awt_bridge.c.
 *
 * Rebuild with caciocavallo17_patches/build.sh.
 */
public class CTCDesktopPeer implements DesktopPeer {
    static {
        // CTCScreen's static initializer loads libpojavexec_awt.so into the boot class loader,
        // which is where this class lives as well (-Xbootclasspath/a).
        try {
            Class.forName("com.github.caciocavallosilano.cacio.ctc.CTCScreen", true,
                    CTCDesktopPeer.class.getClassLoader());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isSupported(Action action) {
        switch (action) {
            case APP_QUIT_HANDLER:
            case APP_ABOUT:
            case BROWSE:
            case OPEN:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void setAboutHandler(AboutHandler handler) {
    }

    @Override
    public void setQuitHandler(QuitHandler handler) {
    }

    @Override
    public void open(File file) throws IOException {
        try {
            openFile(file.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("Android bridge unavailable", e);
        }
    }

    @Override
    public void edit(File file) throws IOException {
        throw new IOException("Action not supported");
    }

    @Override
    public void print(File file) throws IOException {
        throw new IOException("Action not supported");
    }

    @Override
    public void mail(URI mailtoURL) throws IOException {
        browse(mailtoURL);
    }

    @Override
    public void browse(URI uri) throws IOException {
        try {
            openUri(uri.toString());
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("Android bridge unavailable", e);
        }
    }

    private static native void openUri(String uri);

    private static native void openFile(String path);
}
