package com.carhome;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes from the FOSSGIS Valhalla server, without traffic. Its terms: at most one request a second,
 * an identifying User-Agent and X-Client-Id, and the OpenStreetMap credit that Home shows.
 */
final class Router {

    interface Callback {
        /** On the main thread; null when no route could be had. */
        void onRoute(Route route);
    }

    private static final String TAG = "Router";
    private static final String SERVER = "https://valhalla1.openstreetmap.de/route?json=";
    private static final String CLIENT_ID = "carhome-headunit";
    private static final long MIN_INTERVAL_MS = 1100;

    private final Background background = new Background();
    /** Worker thread only. */
    private long lastRequest;

    /** bearing is the car's heading in degrees, or NaN; it keeps a new route from starting with a U-turn. */
    void route(double fromLat, double fromLon, float bearing, double toLat, double toLon, String name, Callback callback) {
        background.run(() -> {
            Route route = null;
            try {
                long wait = lastRequest + MIN_INTERVAL_MS - SystemClock.elapsedRealtime();
                if (wait > 0) Thread.sleep(wait);
                lastRequest = SystemClock.elapsedRealtime();
                JSONObject from = new JSONObject().put("lat", fromLat).put("lon", fromLon);
                if (!Float.isNaN(bearing)) from.put("heading", Math.round(bearing)).put("heading_tolerance", 60);
                JSONObject request = new JSONObject()
                        .put("locations", new JSONArray().put(from).put(new JSONObject().put("lat", toLat).put("lon", toLon)))
                        .put("costing", "auto")
                        .put("units", "kilometers")
                        .put("directions_options", new JSONObject().put("language", "en-US"));
                route = parse(Http.getText(SERVER + URLEncoder.encode(request.toString(), "UTF-8"),
                        "X-Client-Id", CLIENT_ID), name);
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "route failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Route result = route;
            background.post(() -> callback.onRoute(result));
        });
    }

    void close() {
        background.close();
    }

    static Route parse(String json, String destination) throws JSONException {
        JSONObject leg = new JSONObject(json).getJSONObject("trip").getJSONArray("legs").getJSONObject(0);
        double[] points = Polyline.decode(leg.getString("shape"), 6);
        JSONArray raw = leg.getJSONArray("maneuvers");
        if (points.length < 4 || raw.length() == 0) throw new JSONException("route without a path");
        List<Route.Maneuver> maneuvers = new ArrayList<>(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            JSONObject m = raw.getJSONObject(i);
            int begin = m.getInt("begin_shape_index");
            if (begin < 0 || begin >= points.length / 2) throw new JSONException("maneuver outside the route");
            maneuvers.add(new Route.Maneuver(m.getInt("type"), m.getString("instruction"), begin, m.optDouble("time", 0)));
        }
        return new Route(points, maneuvers, destination);
    }
}
