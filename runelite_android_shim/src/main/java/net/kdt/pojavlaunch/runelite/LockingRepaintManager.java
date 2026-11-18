package net.kdt.pojavlaunch.runelite;

import javax.swing.RepaintManager;

/**
 * Paints Swing's dirty regions while holding cacio's CTCScreen.SCREEN_LOCK, which the Android
 * blitter also takes while copying the screen buffer. Without it the copy (60 times a second,
 * from another thread) regularly caught panels in the middle of a paint batch, so the sidebar and
 * plugin panels flickered whenever they changed.
 */
final class LockingRepaintManager extends RepaintManager {
    static final Object LOCK = findLock();

    private static Object findLock() {
        try {
            return Class.forName("com.github.caciocavallosilano.cacio.ctc.CTCScreen").getField("SCREEN_LOCK").get(null);
        } catch (Throwable t) {
            System.err.println("LockingRepaintManager: no CTCScreen.SCREEN_LOCK (" + t + "), painting unlocked");
            return null;
        }
    }

    static void install() {
        if (LOCK == null) return;
        RepaintManager.setCurrentManager(new LockingRepaintManager());
    }

    @Override
    public void paintDirtyRegions() {
        synchronized (LOCK) {
            super.paintDirtyRegions();
        }
    }
}
