package com.carhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

/** Place search through Photon (OpenStreetMap search, no key), and the last few destinations. */
final class Places {

    static final class Place {
        final String name;
        final String detail;
        final double lat;
        final double lon;

        Place(String name, String detail, double lat, double lon) {
            this.name = name;
            this.detail = detail;
            this.lat = lat;
            this.lon = lon;
        }
    }

    interface Callback {
        /** On the main thread; failed means the search itself did not work, not that it found nothing. */
        void onPlaces(String query, List<Place> places, boolean failed);
    }

    private static final String TAG = "Places";
    private static final int MAX_RECENT = 6;

    private final SharedPreferences prefs;
    private final Background background = new Background();
    private Future<?> pending;

    Places(Context context) {
        prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE);
    }

    /** Results biased towards lat, lon; a newer search replaces one still running. */
    void search(String query, double lat, double lon, Callback callback) {
        if (pending != null) pending.cancel(true);
        pending = background.run(() -> {
            List<Place> places = new ArrayList<>();
            boolean failed = false;
            try {
                String url = String.format(Locale.US, "https://photon.komoot.io/api/?limit=8&lat=%.4f&lon=%.4f&q=%s",
                        lat, lon, URLEncoder.encode(query, "UTF-8"));
                JSONArray features = new JSONObject(Http.getText(url)).getJSONArray("features");
                for (int i = 0; i < features.length(); i++) {
                    Place p = place(features.getJSONObject(i));
                    if (p != null) places.add(p);
                }
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "search failed", e);
                failed = true;
            }
            if (Thread.currentThread().isInterrupted()) return;
            boolean result = failed;
            background.post(() -> callback.onPlaces(query, places, result));
        });
    }

    List<Place> recent() {
        List<Place> places = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                places.add(new Place(o.getString("name"), o.optString("detail"), o.getDouble("lat"), o.getDouble("lon")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "recent places unreadable", e);
        }
        return places;
    }

    void remember(Place p) {
        List<Place> places = recent();
        JSONArray a = new JSONArray();
        try {
            a.put(json(p));
            for (Place old : places) {
                if (a.length() >= MAX_RECENT) break;
                if (old.name.equals(p.name) && Math.abs(old.lat - p.lat) < 1e-4 && Math.abs(old.lon - p.lon) < 1e-4) continue;
                a.put(json(old));
            }
        } catch (JSONException e) {
            Log.w(TAG, "could not save place", e);
            return;
        }
        prefs.edit().putString("recent", a.toString()).apply();
    }

    void close() {
        background.close();
    }

    private static JSONObject json(Place p) throws JSONException {
        return new JSONObject().put("name", p.name).put("detail", p.detail).put("lat", p.lat).put("lon", p.lon);
    }

    /** A Photon feature as "Name" plus "Street 12, District, City". */
    private static Place place(JSONObject feature) throws JSONException {
        JSONObject props = feature.getJSONObject("properties");
        JSONArray coords = feature.getJSONObject("geometry").getJSONArray("coordinates");
        String street = props.optString("street", "");
        String number = props.optString("housenumber", "");
        String streetLine = number.isEmpty() ? street : street + " " + number;
        String name = props.optString("name", "");
        if (name.isEmpty()) name = streetLine;
        if (name.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        if (!streetLine.isEmpty() && !streetLine.equals(name)) parts.add(streetLine);
        for (String key : new String[] {"district", "city", "state"}) {
            String v = Weather.shortPlace(props.optString(key, ""));
            if (v != null && !v.isEmpty() && !parts.contains(v) && !v.equals(name)) parts.add(v);
            if (parts.size() >= 3) break;
        }
        return new Place(name, String.join(", ", parts), coords.getDouble(1), coords.getDouble(0));
    }
}
