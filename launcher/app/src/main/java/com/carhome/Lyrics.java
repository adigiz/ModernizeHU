package com.carhome;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lyrics from LRCLIB: timed lines when the song has synced lyrics, plain lines otherwise. */
final class Lyrics {

    private static final Pattern LRC_LINE = Pattern.compile("^\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]\\s*(.*)$");

    final long[] times;
    final String[] lines;
    final boolean synced;
    /** The LRCLIB text these were parsed from, kept for the on-disk cache. */
    final String raw;

    private Lyrics(long[] times, String[] lines, boolean synced, String raw) {
        this.times = times;
        this.lines = lines;
        this.synced = synced;
        this.raw = raw;
    }

    /** Null for instrumentals and entries without any lyrics. */
    static Lyrics from(JSONObject o) {
        if (o.optBoolean("instrumental")) return null;
        if (!o.isNull("syncedLyrics")) {
            Lyrics l = parse(o.optString("syncedLyrics"), true);
            if (l != null) return l;
        }
        if (!o.isNull("plainLyrics")) return parse(o.optString("plainLyrics"), false);
        return null;
    }

    static Lyrics parse(String raw, boolean synced) {
        return synced ? parseSynced(raw) : parsePlain(raw);
    }

    private static Lyrics parseSynced(String text) {
        List<Long> times = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\n")) {
            Matcher m = LRC_LINE.matcher(raw.trim());
            if (!m.matches() || m.group(3).isEmpty()) continue;
            times.add(Long.parseLong(m.group(1)) * 60_000 + Math.round(Double.parseDouble(m.group(2)) * 1000));
            lines.add(m.group(3));
        }
        if (lines.isEmpty()) return null;
        long[] t = new long[times.size()];
        for (int i = 0; i < t.length; i++) t[i] = times.get(i);
        return new Lyrics(t, lines.toArray(new String[0]), true, text);
    }

    private static Lyrics parsePlain(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\n")) {
            if (!raw.trim().isEmpty()) lines.add(raw.trim());
        }
        return lines.isEmpty() ? null : new Lyrics(new long[0], lines.toArray(new String[0]), false, text);
    }

    /** The line being sung at this position, or -1 before the first one. */
    int indexAt(long positionMs) {
        int lo = 0;
        int hi = times.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= positionMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }
}
