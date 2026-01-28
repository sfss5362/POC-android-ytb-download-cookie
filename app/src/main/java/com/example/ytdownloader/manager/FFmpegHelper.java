package com.example.ytdownloader.manager;

import android.util.Log;

import java.io.File;

public class FFmpegHelper {
    private static final String TAG = "FFmpegHelper";

    public interface MergeCallback {
        void onProgress(int progress);
        void onSuccess(String outputPath);
        void onError(String error);
    }

    public static void mergeVideoAudio(String videoPath, String audioPath, String outputPath, MergeCallback callback) {
        // FFmpeg library not available - merge not supported
        // TODO: Add FFmpeg AAR manually to enable merge functionality
        callback.onError("Merge not supported - FFmpeg library not available. Video and audio downloaded separately.");
    }

    public static void cancel() {
        // No-op when FFmpeg not available
    }
}
