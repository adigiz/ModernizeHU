package com.carhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * OpenFreeMap vector tiles (no key; the OpenStreetMap credit is on Home), drawn into bitmaps off the
 * main thread and kept on disk, so roads already driven show without internet.
 */
final class MapTiles {

    interface Callback {
        /** On the main thread; bitmap is null when the tile could not be had. */
        void onTile(String key, Bitmap bitmap);
    }

    /** OpenFreeMap's most detailed zoom; lower ones show a whole route. */
    static final int MAX_ZOOM = 14;

    private static final String TAG = "MapTiles";
    private static final String TILEJSON = "https://tiles.openfreemap.org/planet";
    // The data version is part of the URL and changes every few weeks; TileJSON names the current one.
    private static final String FALLBACK_TEMPLATE = "https://tiles.openfreemap.org/planet/20261018_080001_pt/{z}/{x}/{y}.pbf";
    private static final long TEMPLATE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_FILES = 4000;
    private static final int TRIM_TO_FILES = 3500;
    private static final int TRIM_EVERY_WRITES = 50;

    private final File dir;
    private final SharedPreferences prefs;
    private final Background background = new Background();
    /** Main thread only. */
    private final Set<String> pending = new HashSet<>();
    /** Worker thread only. */
    private int writesSinceTrim;

    MapTiles(Context context) {
        dir = new File(context.getFilesDir(), "tiles");
        prefs = context.getSharedPreferences("map", Context.MODE_PRIVATE);
    }

    static String key(int z, int x, int y) {
        return z + "/" + x + "/" + y;
    }

    void request(int z, int x, int y, int sizePx, Callback callback) {
        String key = key(z, x, y);
        if (!pending.add(key)) return;
        background.run(() -> {
            Bitmap bitmap = null;
            try {
                byte[] data = load(z, x, y);
                if (data != null) bitmap = MapRenderer.render(VectorTile.decode(data), sizePx, z);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "tile " + key + " failed", e);
            }
            Bitmap result = bitmap;
            background.post(() -> {
                pending.remove(key);
                callback.onTile(key, result);
            });
        });
    }

    void close() {
        background.close();
    }

    /** The tile's bytes from disk or the network; an empty array is a tile with nothing on it. */
    private byte[] load(int z, int x, int y) throws IOException {
        File file = new File(dir, z + "_" + x + "_" + y + ".pbf");
        if (file.exists()) {
            CacheFiles.touch(file);
            return CacheFiles.read(file);
        }
        byte[] data = download(z, x, y, false);
        if (data == null) data = download(z, x, y, true);
        if (data == null) data = new byte[0];
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("could not create " + dir);
        CacheFiles.write(file, data);
        if (++writesSinceTrim >= TRIM_EVERY_WRITES) {
            writesSinceTrim = 0;
            CacheFiles.trim(dir, MAX_FILES, TRIM_TO_FILES);
        }
        return data;
    }

    /** Null when the server has no such tile; a stale template is refreshed once before giving up. */
    private byte[] download(int z, int x, int y, boolean freshTemplate) throws IOException {
        String url = template(freshTemplate).replace("{z}", String.valueOf(z))
                .replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
        byte[] body = Http.get(url);
        if (body == null) return null;
        // Tile servers often send gzipped tiles without saying so in Content-Encoding.
        return isGzip(body) ? gunzip(body) : body;
    }

    private String template(boolean refresh) {
        String saved = prefs.getString("template", null);
        long savedAt = prefs.getLong("template_at", 0);
        if (!refresh && saved != null && System.currentTimeMillis() - savedAt < TEMPLATE_MAX_AGE_MS) return saved;
        try {
            byte[] json = Http.get(TILEJSON);
            if (json != null) {
                String t = new JSONObject(new String(json, StandardCharsets.UTF_8)).getJSONArray("tiles").getString(0);
                prefs.edit().putString("template", t).putLong("template_at", System.currentTimeMillis()).apply();
                return t;
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "TileJSON failed", e);
        }
        return saved != null ? saved : FALLBACK_TEMPLATE;
    }

    private static boolean isGzip(byte[] b) {
        return b.length > 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b;
    }

    private static byte[] gunzip(byte[] b) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            return CacheFiles.readAll(in);
        }
    }
}
