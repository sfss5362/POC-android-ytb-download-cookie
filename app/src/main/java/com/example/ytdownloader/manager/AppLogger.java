package com.example.ytdownloader.manager;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class AppLogger {
    private static final StringBuilder buffer = new StringBuilder();
    private static final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static final CopyOnWriteArrayList<LogListener> listeners = new CopyOnWriteArrayList<>();

    public interface LogListener {
        void onNewLog(String fullLog);
    }

    public static void addListener(LogListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(LogListener listener) {
        listeners.remove(listener);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        append("D", tag, msg);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        append("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        append("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        append("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        StringBuilder sb = new StringBuilder(msg);
        if (t != null) {
            sb.append("\n  ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            // Include first few stack frames
            StackTraceElement[] stack = t.getStackTrace();
            int limit = Math.min(5, stack.length);
            for (int i = 0; i < limit; i++) {
                sb.append("\n    at ").append(stack[i].toString());
            }
            if (stack.length > limit) {
                sb.append("\n    ... ").append(stack.length - limit).append(" more");
            }
        }
        append("E", tag, sb.toString());
    }

    private static synchronized void append(String level, String tag, String msg) {
        String time = timeFmt.format(new Date());
        String line = "[" + time + "] " + level + "/" + tag + ": " + msg + "\n";
        buffer.append(line);
        String full = buffer.toString();
        for (LogListener listener : listeners) {
            listener.onNewLog(full);
        }
    }

    public static synchronized String getLog() {
        return buffer.toString();
    }

    public static synchronized void clear() {
        buffer.setLength(0);
    }
}
