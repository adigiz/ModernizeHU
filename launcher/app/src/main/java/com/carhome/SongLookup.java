package com.carhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Cover art from the iTunes Search API and lyrics from LRCLIB, sent only the title and artist and
 * kept on disk. A song with no match is asked about again after two weeks; a failed request is not.
 */
final class SongLookup {

    static final class Result {
        final String key;
        Bitmap cover;
        Lyrics lyrics;

        Result(String key) {
            this.key = key;
        }
    }

    interface Callback {
        void onResult(Result result);
    }

    private static final String TAG = "SongLookup";
    /** Up to two files a song: cover and lyrics, or their miss markers. */
    private static final int MAX_FILES = 800;
    private static final int TRIM_TO_FILES = 700;
    private static final long RETRY_MISS_MS = 14L * 24 * 60 * 60 * 1000;
    /** Each cover is 600 x 600 in RGB_565, about 0.7 MB. */
    private static final int MEMORY_ENTRIES = 8;

    private final File dir;
    private final Background background = new Background();
    private final Map<String, Result> memory = new LinkedHashMap<String, Result>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > MEMORY_ENTRIES;
        }
    };
    private Future<?> pending;

    SongLookup(Context context) {
        dir = new File(context.getFilesDir(), "songs");
    }

    static String key(String title, String artist) {
        return title + "\n" + (artist != null ? artist : "");
    }

    /** Calls back on the main thread; a newer lookup replaces one still in flight. */
    void lookup(String title, String artist, boolean wantCover, Callback callback) {
        String key = key(title, artist);
        Result hit = memory.get(key);
        if (hit != null) {
            callback.onResult(hit);
            return;
        }
        if (pending != null) pending.cancel(true);
        pending = background.run(() -> {
            String name = fileName(key);
            Result r = new Result(key);
            boolean settled = true;
            if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "could not create " + dir);
            if (wantCover) {
                try {
                    r.cover = cover(name, title, artist);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "cover lookup failed", e);
                    settled = false;
                }
            }
            try {
                r.lyrics = lyrics(name, title, artist);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "lyrics lookup failed", e);
                settled = false;
            }
            CacheFiles.trim(dir, MAX_FILES, TRIM_TO_FILES);
            boolean keep = settled;
            background.post(() -> {
                if (keep) memory.put(key, r);
                callback.onResult(r);
            });
        });
    }

    void close() {
        background.close();
    }

    private Bitmap cover(String name, String title, String artist) throws IOException {
        File jpg = new File(dir, name + ".jpg");
        File miss = new File(dir, name + ".nocover");
        byte[] image;
        if (jpg.exists()) {
            image = CacheFiles.read(jpg);
            CacheFiles.touch(jpg);
        } else if (CacheFiles.isNewerThan(miss, RETRY_MISS_MS)) {
            return null;
        } else {
            image = fetchCover(title, artist);
            if (image == null) {
                CacheFiles.write(miss, new byte[0]);
                return null;
            }
            CacheFiles.write(jpg, image);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(image, 0, image.length, opts);
    }

    private Lyrics lyrics(String name, String title, String artist) throws IOException {
        File synced = new File(dir, name + ".lrc");
        File plain = new File(dir, name + ".txt");
        File miss = new File(dir, name + ".nolyrics");
        if (synced.exists()) {
            CacheFiles.touch(synced);
            return Lyrics.parse(new String(CacheFiles.read(synced), StandardCharsets.UTF_8), true);
        }
        if (plain.exists()) {
            CacheFiles.touch(plain);
            return Lyrics.parse(new String(CacheFiles.read(plain), StandardCharsets.UTF_8), false);
        }
        if (CacheFiles.isNewerThan(miss, RETRY_MISS_MS)) return null;
        Lyrics l = fetchLyrics(title, artist);
        if (l == null) {
            CacheFiles.write(miss, new byte[0]);
            return null;
        }
        CacheFiles.write(l.synced ? synced : plain, l.raw.getBytes(StandardCharsets.UTF_8));
        return l;
    }

    /** The cover JPEG, or null when iTunes has no match. */
    private static byte[] fetchCover(String title, String artist) throws IOException {
        byte[] body = Http.get("https://itunes.apple.com/search?media=music&entity=song&limit=1&term="
                + enc(artist != null ? title + " " + artist : title));
        try {
            JSONArray results = body == null ? null
                    : new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            String url = results.getJSONObject(0).optString("artworkUrl100", "");
            if (url.isEmpty()) return null;
            return Http.get(url.replace("100x100bb", "600x600bb"));
        } catch (JSONException e) {
            throw new IOException("unreadable iTunes answer", e);
        }
    }

    /** Synced lyrics if LRCLIB has them, else plain ones, else null. */
    private static Lyrics fetchLyrics(String title, String artist) throws IOException {
        try {
            if (artist != null) {
                byte[] exact = Http.get("https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist));
                if (exact != null) {
                    Lyrics l = Lyrics.from(new JSONObject(new String(exact, StandardCharsets.UTF_8)));
                    if (l != null) return l;
                }
            }
            byte[] search = Http.get("https://lrclib.net/api/search?q=" + enc(artist != null ? title + " " + artist : title));
            if (search == null) return null;
            JSONArray results = new JSONArray(new String(search, StandardCharsets.UTF_8));
            Lyrics plain = null;
            for (int i = 0; i < results.length(); i++) {
                Lyrics l = Lyrics.from(results.getJSONObject(i));
                if (l == null) continue;
                if (l.synced) return l;
                if (plain == null) plain = l;
            }
            return plain;
        } catch (JSONException e) {
            throw new IOException("unreadable LRCLIB answer", e);
        }
    }

    private static String fileName(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }
}
