package com.example.ytdownloader.manager;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;

public class FFmpegHelper {
    private static final String TAG = "FFmpegHelper";

    public interface MergeCallback {
        void onProgress(int progress);
        void onSuccess(String outputPath);
        void onError(String error);
    }

    public static void mergeVideoAudio(String videoPath, String audioPath, String outputPath, MergeCallback callback) {
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

        FFmpegSession session = FFmpegKit.execute(command);

        if (ReturnCode.isSuccess(session.getReturnCode())) {
            // Success - cleanup temp files
            videoFile.delete();
            audioFile.delete();
            callback.onSuccess(outputPath);
        } else {
            String errorMsg = "FFmpeg merge failed with code: " + session.getReturnCode();
            Log.e(TAG, errorMsg);
            Log.e(TAG, "FFmpeg output: " + session.getOutput());
            callback.onError(errorMsg);
        }
    }

    public static void cancel() {
        FFmpegKit.cancel();
    }
}
