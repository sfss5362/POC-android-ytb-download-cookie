package com.example.ytdownloader.manager;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class FFmpegHelper {
    private static final String TAG = "FFmpegHelper";
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB buffer

    public interface MergeCallback {
        void onProgress(int progress);
        void onSuccess(String outputPath);
        void onError(String error);
    }

    public static void mergeVideoAudio(String videoPath, String audioPath, String outputPath, MergeCallback callback) {
        new Thread(() -> {
            try {
                doMerge(videoPath, audioPath, outputPath, callback);
            } catch (Exception e) {
                Log.e(TAG, "Merge failed", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }

    private static void doMerge(String videoPath, String audioPath, String outputPath, MergeCallback callback) throws IOException {
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

        Log.d(TAG, "Starting merge: video=" + videoPath + ", audio=" + audioPath);

        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;

        try {
            videoExtractor.setDataSource(videoPath);
            audioExtractor.setDataSource(audioPath);

            // Find video track
            int videoTrackIndex = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat format = videoExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoFormat = format;
                    break;
                }
            }

            // Find audio track
            int audioTrackIndex = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                MediaFormat format = audioExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = format;
                    break;
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                callback.onError("No video track found");
                return;
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                callback.onError("No audio track found");
                return;
            }

            // Create muxer
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            videoExtractor.selectTrack(videoTrackIndex);
            audioExtractor.selectTrack(audioTrackIndex);

            int muxerVideoTrack = muxer.addTrack(videoFormat);
            int muxerAudioTrack = muxer.addTrack(audioFormat);

            muxer.start();

            // Get duration for progress
            long videoDuration = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;

            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            // Write video track
            Log.d(TAG, "Writing video track...");
            while (true) {
                bufferInfo.offset = 0;
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0);

                if (bufferInfo.size < 0) {
                    break;
                }

                bufferInfo.presentationTimeUs = videoExtractor.getSampleTime();
                bufferInfo.flags = videoExtractor.getSampleFlags();

                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo);

                // Update progress (0-50% for video)
                if (videoDuration > 0) {
                    int progress = (int) ((bufferInfo.presentationTimeUs * 50) / videoDuration);
                    callback.onProgress(Math.min(progress, 50));
                }

                videoExtractor.advance();
            }

            // Write audio track
            Log.d(TAG, "Writing audio track...");
            long audioDuration = audioFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? audioFormat.getLong(MediaFormat.KEY_DURATION) : videoDuration;

            while (true) {
                bufferInfo.offset = 0;
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0);

                if (bufferInfo.size < 0) {
                    break;
                }

                bufferInfo.presentationTimeUs = audioExtractor.getSampleTime();
                bufferInfo.flags = audioExtractor.getSampleFlags();

                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo);

                // Update progress (50-100% for audio)
                if (audioDuration > 0) {
                    int progress = 50 + (int) ((bufferInfo.presentationTimeUs * 50) / audioDuration);
                    callback.onProgress(Math.min(progress, 100));
                }

                audioExtractor.advance();
            }

            Log.d(TAG, "Merge completed successfully");

            // Cleanup temp files
            videoFile.delete();
            audioFile.delete();

            callback.onProgress(100);
            callback.onSuccess(outputPath);

        } finally {
            videoExtractor.release();
            audioExtractor.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing muxer", e);
                }
            }
        }
    }

    public static void cancel() {
        // MediaMuxer doesn't support cancellation directly
        // The merge runs in a separate thread
    }
}
