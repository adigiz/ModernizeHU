package com.carhome;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One worker thread whose results reach the main thread only until {@link #close}, so none outlive Home. */
final class Background {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    /** Null once closed. */
    Future<?> run(Runnable task) {
        return closed ? null : worker.submit(task);
    }

    void post(Runnable result) {
        main.post(() -> {
            if (!closed) result.run();
        });
    }

    void close() {
        closed = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
