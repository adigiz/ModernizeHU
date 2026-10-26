package com.carhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/**
 * Weather from Open-Meteo and the area's name from Nominatim, both keyless and sent only a rounded
 * position. The last answer is kept so Home shows something offline.
 */
final class Weather {

    static final class Now {
        final int temperature;
        final int code;
        final boolean day;
        final String area;

        Now(int temperature, int code, boolean day, String area) {
            this.temperature = temperature;
            this.code = code;
            this.day = day;
            this.area = area;
        }
    }

    interface Listener {
        void onWeather(Now now);
    }

    private static final String TAG = "Weather";
    private static final long WEATHER_EVERY_MS = 20 * 60 * 1000;
    private static final float WEATHER_MOVE_M = 5000;
    /** Nominatim asks for light use, so the name is looked up only after a real move. */
    private static final float AREA_MOVE_M = 2000;
    /** Indonesian administrative titles, longest first, left off a place name on a small screen. */
    private static final String[] TITLES = {
            "Daerah Khusus Ibukota ", "Daerah Khusus ", "Daerah Istimewa ", "Kota Administrasi ",
            "Kabupaten Administrasi ", "Kota ", "Kabupaten ", "Provinsi ",
    };

    private final SharedPreferences prefs;
    private final Listener listener;
    private final Background background = new Background();
    private Location weatherAt;
    private long weatherTime;
    private Location areaAt;
    private boolean busy;

    Weather(Context context, Listener listener) {
        this.listener = listener;
        prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** The answer kept from last time, or null. */
    Now saved() {
        if (!prefs.contains("code")) return null;
        return new Now(prefs.getInt("temp", 0), prefs.getInt("code", 0), prefs.getBoolean("day", true),
                prefs.getString("area", ""));
    }

    void update(Location here) {
        if (here == null || busy) return;
        long now = System.currentTimeMillis();
        boolean weatherDue = weatherAt == null || now - weatherTime > WEATHER_EVERY_MS
                || here.distanceTo(weatherAt) > WEATHER_MOVE_M;
        boolean areaDue = areaAt == null || here.distanceTo(areaAt) > AREA_MOVE_M;
        if (!weatherDue && !areaDue) return;
        double lat = Math.round(here.getLatitude() * 1000) / 1000.0;
        double lon = Math.round(here.getLongitude() * 1000) / 1000.0;
        Location at = new Location(here);
        busy = background.run(() -> {
            Now old = saved();
            int temp = old != null ? old.temperature : 0;
            int code = old != null ? old.code : 0;
            boolean day = old == null || old.day;
            String area = old != null ? old.area : "";
            boolean gotWeather = false;
            boolean gotArea = false;
            try {
                if (weatherDue) {
                    JSONObject current = new JSONObject(Http.getText(String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day&timezone=auto",
                            lat, lon))).getJSONObject("current");
                    temp = (int) Math.round(current.getDouble("temperature_2m"));
                    code = current.getInt("weather_code");
                    day = current.getInt("is_day") == 1;
                    gotWeather = true;
                }
                if (areaDue) {
                    JSONObject address = new JSONObject(Http.getText(String.format(Locale.US,
                            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&lat=%.3f&lon=%.3f",
                            lat, lon))).optJSONObject("address");
                    if (address != null) {
                        area = areaName(address);
                        gotArea = true;
                    }
                }
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "weather lookup failed", e);
            }
            Now result = gotWeather || gotArea || old != null ? new Now(temp, code, day, area) : null;
            if (gotWeather || gotArea) {
                prefs.edit().putInt("temp", temp).putInt("code", code).putBoolean("day", day).putString("area", area).apply();
            }
            boolean weatherDone = gotWeather;
            boolean areaDone = gotArea;
            background.post(() -> {
                busy = false;
                if (weatherDone) {
                    weatherAt = at;
                    weatherTime = System.currentTimeMillis();
                }
                if (areaDone) areaAt = at;
                if (result != null) listener.onWeather(result);
            });
        }) != null;
    }

    void close() {
        background.close();
    }

    /** "Olo · Padang": the neighbourhood, then the town. */
    private static String areaName(JSONObject a) {
        String local = shortPlace(first(a, "suburb", "village", "neighbourhood", "city_district", "quarter"));
        String town = shortPlace(first(a, "city", "town", "municipality", "county", "state"));
        if (local == null) return town != null ? town : "";
        if (town == null || town.equals(local)) return local;
        return local + " · " + town;
    }

    /** "Daerah Khusus Ibukota Jakarta" becomes "Jakarta", "Kabupaten Bogor" becomes "Bogor". */
    static String shortPlace(String name) {
        if (name == null) return null;
        for (String title : TITLES) {
            if (name.startsWith(title) && name.length() > title.length()) return name.substring(title.length());
        }
        return name;
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    /** WMO weather interpretation codes. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code == 61 || code == 80) return "Light rain";
        if (code == 63 || code == 81 || code == 66) return "Rain";
        if (code == 65 || code == 82 || code == 67) return "Heavy rain";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    static int icon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.w_sun : R.drawable.w_moon;
        if (code <= 48) return R.drawable.w_cloud;
        if (code >= 95) return R.drawable.w_storm;
        return R.drawable.w_rain;
    }
}
