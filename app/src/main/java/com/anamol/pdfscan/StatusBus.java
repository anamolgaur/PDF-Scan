package com.anamol.pdfscan;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** One-line progress messages from background work (indexing, Drive uploads) for the screens. */
final class StatusBus {
    interface Listener {
        /** text is the current message, or null when nothing is running. */
        void onStatus(String text);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String indexing;
    private static volatile String drive;
    private static volatile String files;

    private StatusBus() {
    }

    static void add(Listener l) {
        LISTENERS.add(l);
    }

    static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    static String current() {
        if (indexing != null) return indexing;
        if (files != null) return files;
        return drive;
    }

    static void indexing(String text) {
        indexing = text;
        post();
    }

    static void files(String text) {
        files = text;
        post();
    }

    static void drive(String text) {
        drive = text;
        post();
    }

    private static void post() {
        final String s = current();
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onStatus(s);
        });
    }
}
