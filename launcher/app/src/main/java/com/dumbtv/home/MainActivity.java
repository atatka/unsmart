package com.dumbtv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Home screen for a TV that is only a monitor: a row of HDMI inputs and Settings, nothing else.
 * After a boot or wake-up it jumps straight to the last used input; Home brings this picker back,
 * Back returns to the input you were on, and the number keys jump to HDMI 1 to 9.
 */
public class MainActivity extends Activity {

    private static final String KEY_BOOT_COUNT = "boot_count";
    private static final String SETTINGS_TAG = "settings";

    private static final int BG = Color.BLACK;
    private static final int TILE = 0xFF1C1C1E;
    private static final int TILE_FOCUSED = 0xFFF2F2F7;
    private static final int TEXT = 0xFFF2F2F7;
    private static final int TEXT_DIM = 0xFF8E8E93;
    private static final int GREEN = 0xFF30D158;
    private static final int AMBER = 0xFFFF9F0A;

    private LinearLayout row;
    private Typeface outfit;
    private float density;
    private boolean resumed;
    /** Set when the screen comes on while this picker is in front: go back to the last input. */
    private boolean pendingWakeSwitch;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::populate;
    private TextView onlineBar;
    private final Runnable onlineCheck = this::checkOnline;

    /** Re-checks internet access whenever the network changes while the picker is open. */
    private final ConnectivityManager.NetworkCallback networkChanges = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            scheduleOnlineCheck();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            scheduleOnlineCheck();
        }

        @Override
        public void onLost(Network network) {
            scheduleOnlineCheck();
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!getResources().getBoolean(R.bool.resume_last_input)) return;
            if (resumed) switchToLastInput();
            else pendingWakeSwitch = true;
        }
    };

    /** Redraws the tiles when a box turns on or off, or a CEC device appears, while the picker is open. */
    private final TvInputManager.TvInputCallback inputChanges = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            scheduleRefresh();
        }

        @Override
        public void onInputAdded(String inputId) {
            scheduleRefresh();
        }

        @Override
        public void onInputRemoved(String inputId) {
            scheduleRefresh();
        }

        @Override
        public void onInputUpdated(String inputId) {
            scheduleRefresh();
        }
    };

    /** Updates the Settings tile when the soundbar's eARC link comes or goes. */
    private final AudioDeviceCallback audioChanges = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            scheduleRefresh();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            scheduleRefresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        outfit = getResources().getFont(R.font.outfit);
        setContentView(buildLayout());
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(screenOn);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        boolean freshBoot = consumeFreshBoot();
        if ((freshBoot || pendingWakeSwitch) && getResources().getBoolean(R.bool.resume_last_input)) {
            pendingWakeSwitch = false;
            if (switchToLastInput()) return;
        }
        pendingWakeSwitch = false;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputChanges, handler);
        ((AudioManager) getSystemService(AUDIO_SERVICE)).registerAudioDeviceCallback(audioChanges, handler);
        if (getResources().getBoolean(R.bool.show_online_warning)) {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(networkChanges, handler);
            scheduleOnlineCheck();
        }
        populate();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputChanges);
        ((AudioManager) getSystemService(AUDIO_SERVICE)).unregisterAudioDeviceCallback(audioChanges);
        if (getResources().getBoolean(R.bool.show_online_warning)) {
            handler.removeCallbacks(onlineCheck);
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkChanges);
        }
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            switchToLastInput();
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
            Inputs.Input in = Inputs.byPort(this, "HDMI " + (keyCode - KeyEvent.KEYCODE_0));
            if (in != null) Inputs.switchTo(this, in.id);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean switchToLastInput() {
        String id = Inputs.currentId(this);
        return id != null && Inputs.switchTo(this, id);
    }

    /** True once per boot: the first time home starts after the TV was switched on from cold. */
    private boolean consumeFreshBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0 || Inputs.prefs(this).getInt(KEY_BOOT_COUNT, -1) == boot) return false;
        Inputs.prefs(this).edit().putInt(KEY_BOOT_COUNT, boot).apply();
        return true;
    }

    private void scheduleRefresh() {
        // CEC changes arrive in bursts; redraw once they settle.
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 400);
    }

    private void scheduleOnlineCheck() {
        handler.removeCallbacks(onlineCheck);
        handler.postDelayed(onlineCheck, 1000);
    }

    /** Shows the info bar while the TV can reach the internet (it should be blocked in the router). */
    private void checkOnline() {
        new Thread(() -> {
            boolean online = Online.check(this);
            runOnUiThread(() -> onlineBar.setVisibility(online ? View.VISIBLE : View.GONE));
        }, "online-check").start();
    }

    /** "Soundbar · Atmos" (or "Soundbar · eARC") for the Settings tile; "Soundbar off" while it sleeps. */
    private String soundSummary() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return getString(R.string.system);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            boolean earc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.getType() == AudioDeviceInfo.TYPE_HDMI_EARC;
            if (!earc && d.getType() != AudioDeviceInfo.TYPE_HDMI_ARC) continue;
            String link = earc ? "eARC" : "ARC";
            for (int e : d.getEncodings()) {
                if (e == AudioFormat.ENCODING_E_AC3_JOC || e == AudioFormat.ENCODING_DOLBY_TRUEHD) {
                    link = "Atmos";
                    break;
                }
            }
            return getString(R.string.soundbar) + "  ·  " + link;
        }
        return getString(R.string.soundbar_off);
    }

    // ---- UI --------------------------------------------------------------------------------------

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        if (getResources().getBoolean(R.bool.show_clock)) addClock(root);

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        root.setClipChildren(false);
        root.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        onlineBar = new TextView(this);
        onlineBar.setText(R.string.online_warning);
        style(onlineBar, 16, 400, TEXT);
        onlineBar.setPadding(dp(18), dp(10), dp(22), dp(10));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(TILE);
        pill.setCornerRadius(dp(22));
        onlineBar.setBackground(pill);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(AMBER);
        dot.setSize(dp(10), dp(10));
        onlineBar.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        onlineBar.setCompoundDrawablePadding(dp(12));
        onlineBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = dp(48);
        root.addView(onlineBar, blp);
        return root;
    }

    /** Clock and date, top left. Off by default: without internet the TV's clock is often wrong. */
    private void addClock(FrameLayout root) {
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        style(clock, 64, 200, TEXT);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        clp.setMargins(dp(64), dp(40), 0, 0);
        root.addView(clock, clp);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, MMMM d");
        style(date, 20, 300, TEXT_DIM);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        dlp.setMargins(dp(68), dp(128), 0, 0);
        root.addView(date, dlp);
    }

    private void populate() {
        // Keep focus on the same tile across live refreshes; on a fresh open, focus the current input.
        View focused = row.findFocus();
        Object keep = focused != null ? focused.getTag() : null;
        String current = keep != null ? keep.toString() : Inputs.currentId(this);

        row.removeAllViews();
        List<Inputs.Input> inputs = Inputs.list(this);
        View focus = null;
        for (Inputs.Input in : inputs) {
            String status = in.state == TvInputManager.INPUT_STATE_CONNECTED ? getString(R.string.state_on)
                    : in.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? getString(R.string.state_standby)
                    : getString(R.string.state_off);
            View tile = tile(in.icon, in.name, in.port + "  ·  " + status,
                    in.state == TvInputManager.INPUT_STATE_CONNECTED, in.state == TvInputManager.INPUT_STATE_DISCONNECTED);
            tile.setTag(in.id);
            tile.setOnClickListener(v -> Inputs.switchTo(this, in.id));
            row.addView(tile);
            if (in.id.equals(current)) focus = tile;
        }
        View settings = tile(R.drawable.ic_settings, getString(R.string.settings), soundSummary(), false, false);
        settings.setTag(SETTINGS_TAG);
        settings.setOnClickListener(v -> openSettings());
        row.addView(settings);
        if (SETTINGS_TAG.equals(current)) focus = settings;
        if (inputs.isEmpty()) Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_LONG).show();

        View target = focus != null ? focus : row.getChildAt(0);
        target.post(target::requestFocus);
    }

    private View tile(int iconRes, String title, String subtitle, boolean live, boolean dim) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.START);
        t.setPadding(dp(22), dp(20), dp(22), dp(18));
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TILE);
        bg.setCornerRadius(dp(18));
        t.setBackground(bg);
        t.setAlpha(dim ? 0.45f : 1f);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_START);
        icon.setImageTintList(ColorStateList.valueOf(TEXT));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
        ilp.weight = 1;  // pushes the labels to the bottom of the tile
        t.addView(icon, ilp);

        TextView name = new TextView(this);
        name.setText(title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        style(name, 22, 500, TEXT);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        style(sub, 14, 400, TEXT_DIM);
        if (live) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(GREEN);
            dot.setSize(dp(8), dp(8));
            sub.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            sub.setCompoundDrawablePadding(dp(8));
        }
        t.addView(name);
        t.addView(sub);

        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? TILE_FOCUSED : TILE);
            icon.setImageTintList(ColorStateList.valueOf(has ? Color.BLACK : TEXT));
            name.setTextColor(has ? Color.BLACK : TEXT);
            sub.setTextColor(has ? 0xFF3A3A3C : TEXT_DIM);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(140).start();
            if (has) v.setAlpha(1f);
            else v.setAlpha(dim ? 0.45f : 1f);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(196), dp(140));
        lp.setMargins(dp(12), 0, dp(12), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.cannot_open, getString(R.string.settings)), Toast.LENGTH_SHORT).show();
        }
    }

    private void style(TextView v, int sp, int weight, int color) {
        v.setTypeface(outfit);
        v.setFontVariationSettings("'wght' " + weight);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
