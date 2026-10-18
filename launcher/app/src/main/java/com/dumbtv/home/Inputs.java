package com.dumbtv.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The TV's HDMI ports as DumbTV sees them, and switching between them. Shared by the picker and KeyService. */
final class Inputs {

    static final String PREFS = "dumbtv";
    static final String KEY_LAST_INPUT = "last_input";

    /** One HDMI port as shown on a tile. */
    static final class Input {
        String id;
        String port;     // "HDMI 2"
        String name;     // "PlayStation 5"
        int state;
        int icon;
    }

    private Inputs() {
    }

    /** HDMI ports in port order, named after config, then the HDMI-CEC device, then the TV's own label. */
    static List<Input> list(Context ctx) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        Map<String, String> names = configMap(ctx, R.array.input_names);
        Map<String, String> icons = configMap(ctx, R.array.input_icons);
        Set<String> hidden = new HashSet<>();
        for (String h : ctx.getResources().getStringArray(R.array.hidden_inputs)) hidden.add(h.trim());

        List<TvInputInfo> all = tim.getTvInputList();
        Map<String, String> cecNames = new HashMap<>();
        for (TvInputInfo info : all) {
            // HDMI-CEC devices show up as child inputs of their port.
            if (info.getParentId() != null) {
                cecNames.put(info.getParentId(), String.valueOf(info.loadLabel(ctx)));
            }
        }
        for (TvInputInfo info : all) {
            if (!info.isPassthroughInput() || info.getParentId() != null) continue;
            if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
            Input in = new Input();
            in.id = info.getId();
            in.port = String.valueOf(info.loadLabel(ctx)).trim();
            if (hidden.contains(in.port)) continue;
            CharSequence custom = info.loadCustomLabel(ctx);
            if (names.containsKey(in.port)) in.name = names.get(in.port);
            else if (cecNames.containsKey(in.id)) in.name = cecNames.get(in.id);
            else if (!TextUtils.isEmpty(custom)) in.name = custom.toString();
            else in.name = in.port;
            in.state = tim.getInputState(in.id);
            in.icon = icon(icons.containsKey(in.port) ? icons.get(in.port) : in.name);
            out.add(in);
        }
        Collections.sort(out, (a, b) -> a.port.compareToIgnoreCase(b.port));
        return out;
    }

    /** Picks a logo by keyword, from config ("playstation") or from the device name ("PlayStation 5"). */
    static int icon(String hint) {
        String h = hint.toLowerCase(Locale.ROOT).replace(" ", "");
        if (h.contains("playstation") || h.contains("ps5") || h.contains("ps4")) return R.drawable.ic_playstation;
        if (h.contains("apple")) return R.drawable.ic_appletv;
        if (h.contains("switch") || h.contains("nintendo")) return R.drawable.ic_nintendoswitch;
        if (h.contains("android") || h.contains("google")) return R.drawable.ic_android;
        return R.drawable.ic_hdmi;
    }

    /** The port with this label ("HDMI 3"), or null if it is missing or hidden. */
    static Input byPort(Context ctx, String port) {
        for (Input in : list(ctx)) {
            if (in.port.equalsIgnoreCase(port.trim())) return in;
        }
        return null;
    }

    /**
     * The input the TV is (or was last) showing. Follows switches made outside this app, such as a
     * console taking over via HDMI-CEC, by reading the vendor's current/last input settings.
     */
    static String currentId(Context ctx) {
        List<Input> inputs = list(ctx);
        String hw = hardwareInput(ctx, inputs, ctx.getString(R.string.vendor_current_input_key));
        if (hw == null) hw = hardwareInput(ctx, inputs, ctx.getString(R.string.vendor_last_input_key));
        return hw != null ? hw : prefs(ctx).getString(KEY_LAST_INPUT, null);
    }

    /** Maps a vendor setting like "17" to the HDMI input whose id ends in "/HW17", if any. */
    private static String hardwareInput(Context ctx, List<Input> inputs, String key) {
        if (TextUtils.isEmpty(key)) return null;
        String v;
        try {
            v = Settings.Secure.getString(ctx.getContentResolver(), key);
        } catch (SecurityException e) {
            return null;
        }
        if (TextUtils.isEmpty(v)) return null;
        for (Input in : inputs) {
            if (in.id.endsWith("/HW" + v.trim())) return in.id;
        }
        return null;
    }

    static boolean switchTo(Context ctx, String inputId) {
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            prefs(ctx).edit().putString(KEY_LAST_INPUT, inputId).apply();
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(ctx, ctx.getString(R.string.cannot_open, inputId), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** "key=value" items of a string-array resource. */
    static Map<String, String> configMap(Context ctx, int arrayRes) {
        Map<String, String> m = new HashMap<>();
        for (String item : ctx.getResources().getStringArray(arrayRes)) {
            int eq = item.indexOf('=');
            if (eq > 0) m.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
        }
        return m;
    }
}
