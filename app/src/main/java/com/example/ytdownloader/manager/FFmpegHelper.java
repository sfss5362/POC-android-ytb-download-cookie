package com.example.ytdownloader.manager;

import android.content.Context;
import android.util.Log;

import com.mzgs.ffmpegx.FFmpeg;
import com.mzgs.ffmpegx.FFmpegCallback;

import java.io.File;

public class FFmpegHelper {
    private static final String TAG = "FFmpegHelper";
    private static FFmpeg ffmpeg;
    private static String currentSessionId;

    public interface MergeCallback {
        void onProgress(int progress);
        void onSuccess(String outputPath);
        void onError(String error);
    }

    public static void init(Context context) {
        if (ffmpeg == null) {
            ffmpeg = FFmpeg.Companion.initialize(context);
        }
    }

    public static void mergeVideoAudio(Context context, String videoPath, String audioPath, String outputPath, MergeCallback callback) {
        init(context);

        File videoFile = new File(videoPath);
        File audioFile = new File(audioPath);

        if (!videoFile.exists()) {
            callback.onError("Video file not found");
            return;
        }

        if (!audioFile.exists()) {
            callback.onError("Audio file not found");
            return;
        }

        // Delete output file if exists
        File outputFile = new File(outputPath);
        if (outputFile.exists()) {
            outputFile.delete();
        }

        String command = String.format("-i \"%s\" -i \"%s\" -c copy -shortest \"%s\"",
                videoPath, audioPath, outputPath);

        Log.d(TAG, "Executing FFmpeg merge command: " + command);

        currentSessionId = ffmpeg.executeAsync(command, new FFmpegCallback() {
            @Override
            public void onStart() {
                Log.d(TAG, "FFmpeg started");
            }

            @Override
            public void onProgress(int progress, long time) {
                Log.d(TAG, "FFmpeg progress: " + progress + "%, time: " + time);
                callback.onProgress(progress);
            }

            @Override
            public void onSuccess() {
                Log.d(TAG, "FFmpeg merge success");
                // Cleanup temp files
                videoFile.delete();
                audioFile.delete();
                callback.onSuccess(outputPath);
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "FFmpeg merge failed: " + error);
                callback.onError(error);
            }

            @Override
            public void onFinish() {
                Log.d(TAG, "FFmpeg finished");
                currentSessionId = null;
            }

            @Override
            public void onOutput(String line) {
                Log.d(TAG, "FFmpeg output: " + line);
            }
        });
    }

    public static void cancel() {
        if (ffmpeg != null && currentSessionId != null) {
            ffmpeg.cancel(currentSessionId);
            currentSessionId = null;
        }
    }
}
