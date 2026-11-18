package com.github.caciocavallosilano.cacio.ctc;

import com.github.caciocavallosilano.cacio.peer.CacioMouseInfoPeer;
import com.github.caciocavallosilano.cacio.peer.managed.EventData;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * Replacement for the CTCAndroidInput in cacio-tta-1.18-SNAPSHOT.jar (Android launcher -> AWT).
 *
 * The original forwarded everything through CTCRobotPeer, which
 * <ul>
 *   <li>tracked buttons with the legacy masks and mapped BUTTON2_DOWN_MASK to 0x20
 *   (ALT_GRAPH_MASK), so the middle button arrived as a modifier-only event with button 0: no
 *   middle-drag camera rotation in OSRS;</li>
 *   <li>never put the button into EventData's extended modifiers, so getButton() was always
 *   NOBUTTON;</li>
 *   <li>ignored EVENT_TYPE_SCROLL (mouseWheel() was empty): no wheel zoom/scrolling;</li>
 *   <li>derived KEY_TYPED chars from its own US keymap instead of the char Android computed, and
 *   sent no shift/ctrl/alt state with mouse events (shift-click drop did not work).</li>
 * </ul>
 * This version builds the EventData itself with extended (…_DOWN_MASK) modifiers.
 */
public class CTCAndroidInput {
    public static final int EVENT_TYPE_CHAR = 1000;
    public static final int EVENT_TYPE_CURSOR_POS = 1003;
    public static final int EVENT_TYPE_KEY = 1005;
    public static final int EVENT_TYPE_MOUSE_BUTTON = 1006;
    public static final int EVENT_TYPE_SCROLL = 1007;

    private static final int BUTTONS = InputEvent.BUTTON1_DOWN_MASK | InputEvent.BUTTON2_DOWN_MASK
            | InputEvent.BUTTON3_DOWN_MASK;
    private static final long MULTI_CLICK_MS = 400L;

    /** Kept for binary compatibility with code that referenced the old field. */
    public static CTCRobotPeer mRobotPeer = new CTCRobotPeer();

    private static int sX, sY;
    private static int sButtons;   // extended button masks currently held
    private static int sKeyMods;   // extended SHIFT/CTRL/ALT/META masks currently held
    private static int sPressButton, sPressX, sPressY, sClickCount;
    private static long sLastPressTime;

    public static synchronized void receiveData(int type, int i1, int i2, int i3, int i4) {
        switch (type) {
            case EVENT_TYPE_CURSOR_POS:
                sX = i1;
                sY = i2;
                post(mouse(sButtons != 0 ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED, sButtons));
                CacioMouseInfoPeer.getInstance().setMouseScreenCoordinates(i1, i2);
                break;
            case EVENT_TYPE_MOUSE_BUTTON:
                mouseButton(toDownMask(i1), i2 == 1);
                break;
            case EVENT_TYPE_SCROLL:
                // i1: wheel notches, positive = wheel moved away from the user (Android AXIS_VSCROLL > 0)
                for (int n = 0; n < Math.abs(i1); n++) {
                    EventData ev = mouse(MouseEvent.MOUSE_WHEEL, sButtons);
                    ev.setButton(i1 > 0 ? 4 : 5); // EventData: button 4 -> rotation -1 (up)
                    post(ev);
                }
                break;
            case EVENT_TYPE_KEY:
                key(i2, (char) i1, i3 == 1);
                break;
            case EVENT_TYPE_CHAR:
                typed((char) i1);
                break;
            default:
                break;
        }
    }

    private static int toDownMask(int buttons) {
        int mask = buttons & BUTTONS;
        if ((buttons & InputEvent.BUTTON1_MASK) != 0) mask |= InputEvent.BUTTON1_DOWN_MASK;
        if ((buttons & InputEvent.BUTTON2_MASK) != 0) mask |= InputEvent.BUTTON2_DOWN_MASK;
        if ((buttons & InputEvent.BUTTON3_MASK) != 0) mask |= InputEvent.BUTTON3_DOWN_MASK;
        return mask;
    }

    private static void mouseButton(int mask, boolean down) {
        if (mask == 0) return;
        long now = System.currentTimeMillis();
        if (down) {
            if ((sButtons & mask) != 0) return;
            sClickCount = (sPressButton == mask && now - sLastPressTime < MULTI_CLICK_MS
                    && Math.abs(sPressX - sX) <= 2 && Math.abs(sPressY - sY) <= 2) ? sClickCount + 1 : 1;
            sPressButton = mask;
            sPressX = sX;
            sPressY = sY;
            sLastPressTime = now;
            sButtons |= mask;
            EventData ev = mouse(MouseEvent.MOUSE_PRESSED, mask);
            ev.setPopup((mask & InputEvent.BUTTON3_DOWN_MASK) != 0);
            post(ev);
        } else {
            if ((sButtons & mask) == 0) return;
            // EventData derives getButton() from the modifiers, so keep the released button in them.
            post(mouse(MouseEvent.MOUSE_RELEASED, mask));
            sButtons &= ~mask;
            if (sPressButton == mask && sPressX == sX && sPressY == sY) {
                post(mouse(MouseEvent.MOUSE_CLICKED, mask));
            }
        }
    }

    private static EventData mouse(int id, int buttons) {
        EventData ev = new EventData();
        ev.setId(id);
        ev.setSource(CTCScreen.getInstance());
        ev.setTime(System.currentTimeMillis());
        ev.setModifiers(buttons | sKeyMods);
        ev.setX(sX);
        ev.setY(sY);
        ev.setButton(0);
        ev.setPopup(false);
        ev.setClickCount(id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED
                || id == MouseEvent.MOUSE_WHEEL ? 0 : sClickCount);
        return ev;
    }

    private static int modifierFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_SHIFT: return InputEvent.SHIFT_DOWN_MASK;
            case KeyEvent.VK_CONTROL: return InputEvent.CTRL_DOWN_MASK;
            case KeyEvent.VK_ALT: return InputEvent.ALT_DOWN_MASK;
            case KeyEvent.VK_META: return InputEvent.META_DOWN_MASK;
            case KeyEvent.VK_ALT_GRAPH: return InputEvent.ALT_GRAPH_DOWN_MASK;
            default: return 0;
        }
    }

    private static void key(int keyCode, char keyChar, boolean down) {
        int mod = modifierFor(keyCode);
        if (down) sKeyMods |= mod;
        EventData ev = keyEvent(down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, keyCode,
                keyChar == 0 ? KeyEvent.CHAR_UNDEFINED : keyChar);
        post(ev);
        if (!down) sKeyMods &= ~mod;
        if (down && keyChar != 0 && keyChar != KeyEvent.CHAR_UNDEFINED
                && (sKeyMods & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) == 0
                && (keyChar >= 0x20 || keyChar == '\n' || keyChar == '\b' || keyChar == '\t' || keyChar == 0x1B)
                && keyChar != 0x7F) {
            typed(keyChar);
        }
    }

    private static void typed(char keyChar) {
        post(keyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, keyChar));
    }

    private static EventData keyEvent(int id, int keyCode, char keyChar) {
        EventData ev = new EventData();
        ev.setSource(CTCScreen.getInstance());
        ev.setId(id);
        ev.setTime(System.currentTimeMillis());
        ev.setModifiers(sKeyMods | sButtons);
        ev.setKeyCode(keyCode);
        ev.setKeyChar(keyChar);
        return ev;
    }

    private static void post(EventData ev) {
        CTCEventSource.getInstance().postEvent(ev);
    }
}
