package com.codyedwin.runelitemobile.launcher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.appcompat.widget.SwitchCompat;

/**
 * Switch that toggles on a plain tap only (same handling as the launcher's row switches, 563027e):
 * SwitchCompat's thumb-drag inside a scrolling list made switches snap back without changing.
 */
public class TapSwitch extends SwitchCompat {
    private float downX, downY;
    private final int slop;

    public TapSwitch(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop() * 3;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                setPressed(true);
                break;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) setChecked(!isChecked());
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                break;
        }
        return true;
    }
}
