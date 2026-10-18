package com.dumbtv.home;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Whether this TV can reach the internet. A dumb TV shouldn't: no ads, no data collection, no updates
 * that undo the setup. Blocked in the router, Android sees the network as a captive portal (not validated)
 * and direct HTTPS connections fail.
 */
final class Online {

    private static final String[] PROBES = {"1.1.1.1", "8.8.8.8"};

    private Online() {
    }

    /** Blocks for up to a few seconds; call off the main thread. */
    static boolean check(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net = cm != null ? cm.getActiveNetwork() : null;
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true;
        // Android's own check can fail for other reasons; an HTTPS connection is the real test.
        for (String host : PROBES) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, 443), 3000);
                return true;
            } catch (IOException ignored) {
                // blocked or unreachable; try the next one
            }
        }
        return false;
    }
}
