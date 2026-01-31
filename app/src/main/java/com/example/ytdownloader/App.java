package com.example.ytdownloader;

import android.app.Application;

import com.example.ytdownloader.manager.AppLogger;

public class App extends Application {
    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.i(TAG, "Application initialized");
    }
}
