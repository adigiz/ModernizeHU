package com.carhome;

import android.util.Log;

import org.conscrypt.Conscrypt;

import java.security.Security;

/** Conscrypt as the first security provider: Android 7.1 stops at TLS 1.2, the routing servers need 1.3. */
final class Tls {

    private static boolean installed;

    private Tls() {
    }

    static void install() {
        if (installed) return;
        installed = true;
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            Log.w("Tls", "Conscrypt unavailable, using the system's TLS", e);
        }
    }
}
