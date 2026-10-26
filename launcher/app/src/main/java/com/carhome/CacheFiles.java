package com.carhome;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;

/** File helpers for the on-disk caches, where a file's modification time marks when it was last used. */
final class CacheFiles {

    private static final String TAG = "CacheFiles";

    private CacheFiles() {
    }

    static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    /** Through a temporary file, so a power cut never leaves half a file behind. */
    static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) throw new IOException("could not save " + f.getName());
    }

    static void touch(File f) {
        if (!f.setLastModified(System.currentTimeMillis())) Log.w(TAG, "could not touch " + f.getName());
    }

    static boolean isNewerThan(File f, long ageMs) {
        return f.exists() && System.currentTimeMillis() - f.lastModified() < ageMs;
    }

    /** Deletes the least recently used files once there are more than max, down to keep. */
    static void trim(File dir, int max, int keep) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= max) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - keep; i++) {
            if (!files[i].delete()) Log.w(TAG, "could not delete " + files[i].getName());
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
