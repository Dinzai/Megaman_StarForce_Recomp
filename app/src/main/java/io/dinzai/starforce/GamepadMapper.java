package io.dinzai.starforce;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Physical controls → DS keys. Face buttons map by position (the right-hand
 * button is DS A), which matches Nintendo layouts and handhelds such as the
 * AYN Thor. {@link #swapFaceButtons} maps by label instead.
 */
final class GamepadMapper {
    boolean swapFaceButtons = false;
    private int keyState = 0, axisState = 0;

    int keys() { return keyState | axisState; }

    /** @return true if the event was a game control. */
    boolean onKey(int keyCode, boolean down) {
        int k = map(keyCode);
        if (k == 0) return false;
        if (down) keyState |= k; else keyState &= ~k;
        return true;
    }

    int map(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_B: return swapFaceButtons ? NativeCore.KEY_B : NativeCore.KEY_A;
            case KeyEvent.KEYCODE_BUTTON_A: return swapFaceButtons ? NativeCore.KEY_A : NativeCore.KEY_B;
            case KeyEvent.KEYCODE_BUTTON_Y: return swapFaceButtons ? NativeCore.KEY_Y : NativeCore.KEY_X;
            case KeyEvent.KEYCODE_BUTTON_X: return swapFaceButtons ? NativeCore.KEY_X : NativeCore.KEY_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return NativeCore.KEY_L;
            case KeyEvent.KEYCODE_BUTTON_R1: return NativeCore.KEY_R;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_ENTER: return NativeCore.KEY_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_SPACE: return NativeCore.KEY_SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: return NativeCore.KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_S: return NativeCore.KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A: return NativeCore.KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D: return NativeCore.KEY_RIGHT;
            case KeyEvent.KEYCODE_X: return NativeCore.KEY_A;
            case KeyEvent.KEYCODE_Z: return NativeCore.KEY_B;
            case KeyEvent.KEYCODE_C: return NativeCore.KEY_X;
            case KeyEvent.KEYCODE_V: return NativeCore.KEY_Y;
            case KeyEvent.KEYCODE_Q: return NativeCore.KEY_L;
            case KeyEvent.KEYCODE_E: return NativeCore.KEY_R;
            default: return 0;
        }
    }

    /** Left stick and hat → D-pad. */
    boolean onMotion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.getAction() != MotionEvent.ACTION_MOVE)
            return false;
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X), y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
            x = e.getAxisValue(MotionEvent.AXIS_X);
            y = e.getAxisValue(MotionEvent.AXIS_Y);
        }
        int k = 0;
        if (x < -0.5f) k |= NativeCore.KEY_LEFT; else if (x > 0.5f) k |= NativeCore.KEY_RIGHT;
        if (y < -0.5f) k |= NativeCore.KEY_UP; else if (y > 0.5f) k |= NativeCore.KEY_DOWN;
        axisState = k;
        return true;
    }

    void reset() { keyState = 0; axisState = 0; }
}
