package com.example.ytdownloader;

import android.app.Application;

import com.example.ytdownloader.manager.AppLogger;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.ffmpeg.FFmpeg;

public class App extends Application {
    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            YoutubeDL.getInstance().init(this);
            FFmpeg.getInstance().init(this);
            AppLogger.i(TAG, "YoutubeDL and FFmpeg initialized");
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to initialize YoutubeDL/FFmpeg", e);
        }
    }
}
