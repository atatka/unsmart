package com.dumbtv.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns the remote's app buttons (Netflix, YouTube, ...) into input shortcuts, everywhere, including
 * while an HDMI input is playing. Mapping lives in config.xml (button_inputs). Accessibility is the only
 * way an app sees these keys before the focused window does; enabled over adb by build-and-install.sh.
 */
public class KeyService extends AccessibilityService {

    private final Map<Integer, String> targets = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        targets.clear();
        for (Map.Entry<String, String> e : Inputs.configMap(this, R.array.button_inputs).entrySet()) {
            try {
                targets.put(Integer.parseInt(e.getKey()), e.getValue());
            } catch (NumberFormatException ignored) {
                // not a key code; skip the line
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String target = targets.get(event.getKeyCode());
        if (target == null) return false;
        // Act on the first key-down only; swallow the repeat and key-up so the stock handler never sees it.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) open(target);
        return true;
    }

    private void open(String target) {
        try {
            if (target.equalsIgnoreCase("picker")) {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (target.equalsIgnoreCase("settings")) {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                Inputs.Input in = Inputs.byPort(this, target);
                if (in != null) Inputs.switchTo(this, in.id);
            }
        } catch (ActivityNotFoundException ignored) {
            // nothing to open; the key is still swallowed
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
