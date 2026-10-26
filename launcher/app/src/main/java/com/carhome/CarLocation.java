package com.carhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Position and speed from the head unit's GPS while Home is on screen. The last position is kept, so
 * the map shows the right place at once after the car starts, before the GPS has a fix.
 */
final class CarLocation implements LocationListener {

    interface Listener {
        void onLocation(Location location);
    }

    private static final String TAG = "CarLocation";
    private static final long GPS_INTERVAL_MS = 1000;
    private static final long NETWORK_INTERVAL_MS = 30_000;
    /** A network position is coarse; while GPS has a recent fix it would only pull the car aside. */
    private static final long GPS_FRESH_MS = 10_000;

    private final Context context;
    private final LocationManager manager;
    private final SharedPreferences prefs;
    private final Listener listener;
    private boolean running;
    private Location lastFix;
    private long lastGpsFix = -GPS_FRESH_MS;

    CarLocation(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        manager = context.getSystemService(LocationManager.class);
        prefs = context.getSharedPreferences("location", Context.MODE_PRIVATE);
    }

    /** The newest position known: the system's last fix, or the one saved on a previous run. */
    Location last() {
        Location best = null;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                for (String provider : manager.getProviders(true)) {
                    Location l = manager.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "location permission withdrawn", e);
            }
        }
        if (best == null && prefs.contains("lat")) {
            best = new Location("saved");
            best.setLatitude(Double.longBitsToDouble(prefs.getLong("lat", 0)));
            best.setLongitude(Double.longBitsToDouble(prefs.getLong("lon", 0)));
        }
        return best;
    }

    void start() {
        if (running) return;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0, this, Looper.getMainLooper());
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, NETWORK_INTERVAL_MS, 0, this, Looper.getMainLooper());
            }
            running = true;
        } catch (SecurityException e) {
            Log.w(TAG, "location permission withdrawn", e);
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        manager.removeUpdates(this);
        if (lastFix != null) save(lastFix);
    }

    /** km/h from a GPS fix; positions from the network carry no speed. */
    static int speedKmh(Location l) {
        return l != null && l.hasSpeed() ? Math.round(l.getSpeed() * 3.6f) : 0;
    }

    private void save(Location l) {
        prefs.edit()
                .putLong("lat", Double.doubleToRawLongBits(l.getLatitude()))
                .putLong("lon", Double.doubleToRawLongBits(l.getLongitude()))
                .apply();
    }

    @Override
    public void onLocationChanged(Location location) {
        long now = SystemClock.elapsedRealtime();
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            lastGpsFix = now;
        } else if (now - lastGpsFix < GPS_FRESH_MS) {
            return;
        }
        lastFix = location;
        listener.onLocation(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Deprecated and never called from Android 10; required by the interface before that.
    }

    @Override
    public void onProviderEnabled(String provider) {
        // Updates resume by themselves.
    }

    @Override
    public void onProviderDisabled(String provider) {
        // The last position stays on the map until the provider returns.
    }
}
