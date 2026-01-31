package com.example.ytdownloader.service;

import android.content.Context;

import com.example.ytdownloader.manager.AppLogger;
import com.example.ytdownloader.manager.CookieStorage;
import com.example.ytdownloader.model.VideoInfo;
import com.github.kiulian.downloader.Config;
import com.github.kiulian.downloader.YoutubeDownloader;
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.model.videos.VideoDetails;
import com.github.kiulian.downloader.model.videos.formats.AudioFormat;
import com.github.kiulian.downloader.model.videos.formats.VideoFormat;
import com.github.kiulian.downloader.model.videos.formats.VideoWithAudioFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YoutubeService {
    private static final String TAG = "YoutubeService";
    private static final String[] BOT_DETECTION_KEYWORDS = {
            "Sign in to confirm",
            "not a bot",
            "LOGIN_REQUIRED",
            "HTTP Error 429"
    };

    private final Context context;
    private final CookieStorage cookieStorage;
    private YoutubeDownloader downloader;

    public interface ParseCallback {
        void onSuccess(VideoInfo videoInfo);
        void onBotDetected();
        void onError(String error);
    }

    public interface DownloadCallback {
        void onProgress(int progress, long downloadedBytes, long totalBytes);
        void onSuccess(String filePath);
        void onError(String error);
    }

    public YoutubeService(Context context) {
        this.context = context;
        this.cookieStorage = new CookieStorage(context);
        this.downloader = createDownloader();
    }

    public void refreshDownloader() {
        this.downloader = createDownloader();
        AppLogger.i(TAG, "Downloader refreshed with new cookies");
    }

    private YoutubeDownloader createDownloader() {
        Config.Builder builder = new Config.Builder()
                .maxRetries(2);

        String cookies = cookieStorage.getCookies();
        if (cookies != null && !cookies.isEmpty()) {
            builder.header("Cookie", cookies);
            AppLogger.d(TAG, "Configured cookies via header");
        }

        return new YoutubeDownloader(builder.build());
    }

    public String extractVideoId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        String[] patterns = {
                "(?:v=|/v/|youtu\\.be/)([a-zA-Z0-9_-]{11})",
                "(?:embed/)([a-zA-Z0-9_-]{11})",
                "(?:shorts/)([a-zA-Z0-9_-]{11})"
        };

        for (String patternStr : patterns) {
            Pattern pattern = Pattern.compile(patternStr);
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        if (url.matches("^[a-zA-Z0-9_-]{11}$")) {
            return url;
        }

        return null;
    }

    public void parseVideo(String videoId, ParseCallback callback) {
        new Thread(() -> {
            try {
                AppLogger.i(TAG, "Parsing video: " + videoId + " (java-youtube-downloader)");

                RequestVideoInfo request = new RequestVideoInfo(videoId);
                Response<com.github.kiulian.downloader.model.videos.VideoInfo> response =
                        downloader.getVideoInfo(request);

                if (!response.ok()) {
                    Throwable error = response.error();
                    String msg = error != null ? error.getMessage() : "Unknown parse error";
                    AppLogger.e(TAG, "Parse error: " + msg);

                    if (msg != null && isBotDetection(msg)) {
                        callback.onBotDetected();
                    } else {
                        callback.onError(msg);
                    }
                    return;
                }

                com.github.kiulian.downloader.model.videos.VideoInfo ytVideo = response.data();
                VideoDetails details = ytVideo.details();

                String title = details.title();
                String author = details.author();
                int duration = details.lengthSeconds();
                List<String> thumbnails = details.thumbnails();
                String thumbnail = (thumbnails != null && !thumbnails.isEmpty())
                        ? thumbnails.get(thumbnails.size() - 1) : null;

                VideoInfo videoInfo = new VideoInfo(videoId, title, author, thumbnail, duration);
                videoInfo.setThumbnailUrls(thumbnails);

                // Find best audio for merging with video-only streams (prefer m4a/aac for MediaMuxer)
                AudioFormat bestAudioForMerge = null;
                List<AudioFormat> audioFormats = ytVideo.audioFormats();
                if (audioFormats != null) {
                    for (AudioFormat af : audioFormats) {
                        String ext = af.extension() != null ? af.extension().value() : "";
                        boolean isAac = "m4a".equals(ext) || "mp4".equals(ext);
                        int abr = af.averageBitrate() != null ? af.averageBitrate() : 0;
                        if (isAac) {
                            if (bestAudioForMerge == null ||
                                    abr > (bestAudioForMerge.averageBitrate() != null ? bestAudioForMerge.averageBitrate() : 0)) {
                                bestAudioForMerge = af;
                            }
                        }
                    }
                    // Fallback: any best audio
                    if (bestAudioForMerge == null && !audioFormats.isEmpty()) {
                        bestAudioForMerge = audioFormats.get(audioFormats.size() - 1);
                    }
                }

                AppLogger.i(TAG, "Best audio for merge: " +
                        (bestAudioForMerge != null ? bestAudioForMerge.itag().id() + " " + bestAudioForMerge.extension() : "none"));

                // --- Build video format list ---
                Map<Integer, VideoInfo.FormatOption> videoDedup = new HashMap<>();

                // 1. Muxed formats (video+audio, usually up to 720p)
                List<VideoWithAudioFormat> muxedFormats = ytVideo.videoWithAudioFormats();
                if (muxedFormats != null) {
                    for (VideoWithAudioFormat f : muxedFormats) {
                        int height = f.height() != null ? f.height() : 0;
                        if (height <= 0) continue;

                        String quality = height + "p";
                        String ext = f.extension() != null ? f.extension().value() : "mp4";
                        long size = f.contentLength() != null ? f.contentLength() : 0;

                        VideoInfo.FormatOption opt = new VideoInfo.FormatOption(
                                String.valueOf(f.itag().id()), quality, ext, ext, size, true, true);
                        opt.setUrl(f.url());

                        boolean isMp4 = "mp4".equals(ext);
                        VideoInfo.FormatOption existing = videoDedup.get(height);
                        if (existing == null || (isMp4 && !"mp4".equals(existing.getExt()))) {
                            videoDedup.put(height, opt);
                            AppLogger.d(TAG, "Muxed: " + quality + " itag=" + f.itag().id() + " ext=" + ext);
                        }
                    }
                }

                // 2. Video-only formats (higher quality, need audio merge)
                List<VideoFormat> videoOnlyFormats = ytVideo.videoFormats();
                if (videoOnlyFormats != null) {
                    for (VideoFormat f : videoOnlyFormats) {
                        int height = f.height() != null ? f.height() : 0;
                        if (height <= 0) continue;

                        String quality = height + "p";
                        String ext = f.extension() != null ? f.extension().value() : "mp4";
                        long size = f.contentLength() != null ? f.contentLength() : 0;

                        // Skip non-mp4 video-only (MediaMuxer only supports mp4)
                        if (!"mp4".equals(ext)) {
                            AppLogger.d(TAG, "Skipped video-only (non-mp4): " + quality + " ext=" + ext);
                            continue;
                        }

                        VideoInfo.FormatOption opt = new VideoInfo.FormatOption(
                                String.valueOf(f.itag().id()), quality, ext, ext, size, false, true);
                        opt.setUrl(f.url());

                        if (bestAudioForMerge != null) {
                            opt.setBestAudioUrl(bestAudioForMerge.url());
                        }

                        VideoInfo.FormatOption existing = videoDedup.get(height);
                        if (existing == null) {
                            videoDedup.put(height, opt);
                            AppLogger.d(TAG, "Video-only: " + quality + " itag=" + f.itag().id());
                        }
                        // Don't replace muxed with video-only at same resolution
                    }
                }

                List<Integer> sortedRes = new ArrayList<>(videoDedup.keySet());
                Collections.sort(sortedRes);
                List<VideoInfo.FormatOption> videoFormatList = new ArrayList<>();
                for (int r : sortedRes) {
                    videoFormatList.add(videoDedup.get(r));
                }
                videoInfo.setVideoFormats(videoFormatList);

                // --- Build audio format list ---
                Map<Integer, VideoInfo.FormatOption> audioDedup = new HashMap<>();
                if (audioFormats != null) {
                    for (AudioFormat f : audioFormats) {
                        int bitrate = f.averageBitrate() != null ? f.averageBitrate() : 0;
                        if (bitrate <= 0) {
                            bitrate = f.bitrate() != null ? f.bitrate() / 1000 : 0;
                        }
                        if (bitrate <= 0) continue;

                        String quality = bitrate + "kbps";
                        String ext = f.extension() != null ? f.extension().value() : "m4a";
                        long size = f.contentLength() != null ? f.contentLength() : 0;

                        VideoInfo.FormatOption opt = new VideoInfo.FormatOption(
                                String.valueOf(f.itag().id()), quality, ext, ext, size, true, false);
                        opt.setUrl(f.url());

                        boolean isAac = "m4a".equals(ext) || "mp4".equals(ext);
                        VideoInfo.FormatOption existingAudio = audioDedup.get(bitrate);
                        if (existingAudio == null || (isAac && !"m4a".equals(existingAudio.getExt()))) {
                            audioDedup.put(bitrate, opt);
                            AppLogger.d(TAG, "Audio: " + quality + " itag=" + f.itag().id() + " ext=" + ext);
                        }
                    }
                }

                List<Integer> sortedAbr = new ArrayList<>(audioDedup.keySet());
                Collections.sort(sortedAbr);
                List<VideoInfo.FormatOption> audioFormatList = new ArrayList<>();
                for (int a : sortedAbr) {
                    audioFormatList.add(audioDedup.get(a));
                }
                videoInfo.setAudioFormats(audioFormatList);

                AppLogger.i(TAG, "Parse success: " + title + " - " +
                        videoFormatList.size() + " video, " + audioFormatList.size() + " audio formats");
                callback.onSuccess(videoInfo);

            } catch (Exception e) {
                String msg = e.getMessage();
                AppLogger.e(TAG, "Exception parsing video: " + (msg != null ? msg : e.getClass().getName()), e);
                if (msg != null && isBotDetection(msg)) {
                    callback.onBotDetected();
                } else {
                    callback.onError(msg != null ? msg : "Unknown error");
                }
            }
        }).start();
    }

    private static boolean isBotDetection(String message) {
        for (String keyword : BOT_DETECTION_KEYWORDS) {
            if (message.contains(keyword)) return true;
        }
        return false;
    }
}
