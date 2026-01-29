package com.example.ytdownloader.service;

import android.content.Context;

import com.example.ytdownloader.manager.AppLogger;
import com.example.ytdownloader.manager.CookieStorage;
import com.example.ytdownloader.model.VideoInfo;
import com.github.kiulian.downloader.YoutubeDownloader;
import com.github.kiulian.downloader.downloader.YoutubeProgressCallback;
import com.github.kiulian.downloader.downloader.request.RequestVideoFileDownload;
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.model.videos.formats.AudioFormat;
import com.github.kiulian.downloader.model.videos.formats.Format;
import com.github.kiulian.downloader.model.videos.formats.VideoFormat;
import com.github.kiulian.downloader.model.videos.formats.VideoWithAudioFormat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YoutubeService {
    private static final String TAG = "YoutubeService";
    private static final String BOT_DETECTION_MESSAGE = "Sign in to confirm you're not a bot";
    private static final String LOGIN_REQUIRED = "LOGIN_REQUIRED";

    private final Context context;
    private final CookieStorage cookieStorage;
    private YoutubeDownloader downloader;

    public interface ParseCallback {
        void onSuccess(VideoInfo videoInfo);
        void onBotDetected();
        void onError(String error);
    }

    public interface DownloadCallback {
        void onProgress(int progress);
        void onSuccess(String filePath);
        void onError(String error);
    }

    public YoutubeService(Context context) {
        this.context = context;
        this.cookieStorage = new CookieStorage(context);
        initDownloader();
    }

    private void initDownloader() {
        String cookies = cookieStorage.getCookies();
        AppLogger.d(TAG, "initDownloader - cookies present: " + (cookies != null && !cookies.isEmpty()));
        if (cookies != null && !cookies.isEmpty()) {
            try {
                File cookieFile = createCookieFile(cookies);
                AppLogger.d(TAG, "Cookie file created: " + cookieFile.getAbsolutePath());
                com.github.kiulian.downloader.Config config = new com.github.kiulian.downloader.Config.Builder()
                        .cookies(cookieFile.getAbsolutePath())
                        .build();
                downloader = new YoutubeDownloader(config);
                AppLogger.i(TAG, "YoutubeDownloader initialized with cookies");
            } catch (IOException e) {
                AppLogger.e(TAG, "Failed to create cookie file", e);
                downloader = new YoutubeDownloader();
            }
        } else {
            AppLogger.d(TAG, "YoutubeDownloader initialized without cookies");
            downloader = new YoutubeDownloader();
        }
    }

    public void refreshDownloader() {
        initDownloader();
    }

    private File createCookieFile(String cookies) throws IOException {
        File cookieFile = new File(context.getCacheDir(), "cookies.txt");
        String cookiesTxt = cookieStorage.convertToCookiesTxt(cookies);
        try (FileWriter writer = new FileWriter(cookieFile)) {
            writer.write(cookiesTxt);
        }
        return cookieFile;
    }

    public String extractVideoId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        // Handle various YouTube URL formats
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

        // Check if the input is already a video ID
        if (url.matches("^[a-zA-Z0-9_-]{11}$")) {
            return url;
        }

        return null;
    }

    public void parseVideo(String videoId, ParseCallback callback) {
        new Thread(() -> {
            try {
                AppLogger.i(TAG, "Parsing video: " + videoId);
                AppLogger.d(TAG, "Has cookies: " + (cookieStorage.getCookies() != null && !cookieStorage.getCookies().isEmpty()));

                RequestVideoInfo request = new RequestVideoInfo(videoId);
                Response<com.github.kiulian.downloader.model.videos.VideoInfo> response = downloader.getVideoInfo(request);

                if (!response.ok()) {
                    String error = response.error().getMessage();
                    AppLogger.e(TAG, "Parse failed: " + error);
                    if (response.error() != null) {
                        AppLogger.e(TAG, "Error details:", response.error());
                    }
                    // Check for bot detection / login required
                    if (error != null && (error.contains(BOT_DETECTION_MESSAGE) || error.contains(LOGIN_REQUIRED))) {
                        AppLogger.w(TAG, "Bot detection / Login required - triggering WebView login");
                        callback.onBotDetected();
                    } else {
                        callback.onError(error != null ? error : "Failed to parse video");
                    }
                    return;
                }

                AppLogger.i(TAG, "Parse successful");

                com.github.kiulian.downloader.model.videos.VideoInfo ytVideoInfo = response.data();

                VideoInfo videoInfo = new VideoInfo(
                        videoId,
                        ytVideoInfo.details().title(),
                        ytVideoInfo.details().author(),
                        ytVideoInfo.details().thumbnails().get(0),
                        ytVideoInfo.details().lengthSeconds()
                );

                // Get video formats (video only, no audio)
                List<VideoInfo.FormatOption> videoFormats = new ArrayList<>();
                for (VideoFormat format : ytVideoInfo.videoFormats()) {
                    videoFormats.add(new VideoInfo.FormatOption(
                            format.itag().id() + "",
                            format.qualityLabel(),
                            format.mimeType(),
                            format.contentLength() != null ? format.contentLength() : 0,
                            false,
                            true
                    ));
                }

                // Get video with audio formats
                for (VideoWithAudioFormat format : ytVideoInfo.videoWithAudioFormats()) {
                    videoFormats.add(new VideoInfo.FormatOption(
                            format.itag().id() + "",
                            format.qualityLabel() + " (with audio)",
                            format.mimeType(),
                            format.contentLength() != null ? format.contentLength() : 0,
                            true,
                            true
                    ));
                }
                videoInfo.setVideoFormats(videoFormats);

                // Get audio formats
                List<VideoInfo.FormatOption> audioFormats = new ArrayList<>();
                for (AudioFormat format : ytVideoInfo.audioFormats()) {
                    audioFormats.add(new VideoInfo.FormatOption(
                            format.itag().id() + "",
                            format.audioQuality().name() + " " + format.averageBitrate() / 1000 + "kbps",
                            format.mimeType(),
                            format.contentLength() != null ? format.contentLength() : 0,
                            true,
                            false
                    ));
                }
                videoInfo.setAudioFormats(audioFormats);

                callback.onSuccess(videoInfo);

            } catch (Exception e) {
                AppLogger.e(TAG, "Exception parsing video: " + e.getClass().getName(), e);
                String message = e.getMessage();
                if (message != null && (message.contains(BOT_DETECTION_MESSAGE) || message.contains(LOGIN_REQUIRED))) {
                    AppLogger.w(TAG, "Bot detection triggered (exception)");
                    callback.onBotDetected();
                } else {
                    AppLogger.e(TAG, "Parse error: " + message);
                    callback.onError(message != null ? message : "Unknown error");
                }
            }
        }).start();
    }

    public void downloadFormat(String videoId, String itag, File outputDir, String filename, DownloadCallback callback) {
        new Thread(() -> {
            try {
                AppLogger.i(TAG, "Download start - videoId: " + videoId + ", itag: " + itag + ", dir: " + outputDir.getAbsolutePath() + ", file: " + filename);

                // Ensure output directory exists
                if (!outputDir.exists()) {
                    boolean created = outputDir.mkdirs();
                    AppLogger.d(TAG, "Output dir created: " + created + " - " + outputDir.getAbsolutePath());
                }
                AppLogger.d(TAG, "Output dir writable: " + outputDir.canWrite() + ", exists: " + outputDir.exists());

                AppLogger.d(TAG, "Re-parsing video info for download...");
                RequestVideoInfo infoRequest = new RequestVideoInfo(videoId);
                Response<com.github.kiulian.downloader.model.videos.VideoInfo> infoResponse = downloader.getVideoInfo(infoRequest);

                if (!infoResponse.ok()) {
                    String error = infoResponse.error() != null ? infoResponse.error().getMessage() : "Unknown error";
                    AppLogger.e(TAG, "Download re-parse failed: " + error);
                    if (infoResponse.error() != null) {
                        AppLogger.e(TAG, "Re-parse error details:", infoResponse.error());
                    }
                    callback.onError("Re-parse failed: " + error);
                    return;
                }

                com.github.kiulian.downloader.model.videos.VideoInfo videoInfo = infoResponse.data();
                AppLogger.i(TAG, "Re-parse success. Video formats: " + videoInfo.videoFormats().size()
                        + ", Audio formats: " + videoInfo.audioFormats().size()
                        + ", VideoWithAudio formats: " + videoInfo.videoWithAudioFormats().size());

                Format format = findFormatByItag(videoInfo, itag);
                if (format == null) {
                    AppLogger.e(TAG, "Format not found for itag: " + itag);
                    // List available itags for debugging
                    StringBuilder available = new StringBuilder("Available itags - Video: ");
                    for (VideoFormat vf : videoInfo.videoFormats()) {
                        available.append(vf.itag().id()).append(" ");
                    }
                    available.append(", Audio: ");
                    for (AudioFormat af : videoInfo.audioFormats()) {
                        available.append(af.itag().id()).append(" ");
                    }
                    available.append(", VideoWithAudio: ");
                    for (VideoWithAudioFormat vaf : videoInfo.videoWithAudioFormats()) {
                        available.append(vaf.itag().id()).append(" ");
                    }
                    AppLogger.d(TAG, available.toString());
                    callback.onError("Format not found for itag: " + itag);
                    return;
                }

                AppLogger.i(TAG, "Format found: mimeType=" + format.mimeType()
                        + ", contentLength=" + (format.contentLength() != null ? format.contentLength() : "null")
                        + ", url=" + (format.url() != null ? format.url().substring(0, Math.min(100, format.url().length())) + "..." : "null"));

                RequestVideoFileDownload downloadRequest = new RequestVideoFileDownload(format)
                        .saveTo(outputDir)
                        .renameTo(filename)
                        .overwriteIfExists(true)
                        .callback(new YoutubeProgressCallback<File>() {
                            @Override
                            public void onDownloading(int progress) {
                                callback.onProgress(progress);
                            }

                            @Override
                            public void onFinished(File file) {
                                AppLogger.i(TAG, "Download callback onFinished: " + file.getAbsolutePath() + ", size: " + file.length());
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                AppLogger.e(TAG, "Download callback onError", throwable);
                            }
                        });

                AppLogger.d(TAG, "Starting file download...");
                Response<File> downloadResponse = downloader.downloadVideoFile(downloadRequest);

                if (downloadResponse.ok()) {
                    File file = downloadResponse.data();
                    AppLogger.i(TAG, "Download response OK, file: " + (file != null ? file.getAbsolutePath() : "null"));
                    if (file != null && file.exists()) {
                        AppLogger.i(TAG, "File exists, size: " + file.length() + " bytes");
                        callback.onSuccess(file.getAbsolutePath());
                    } else {
                        // Try to find file manually
                        File expected = new File(outputDir, filename);
                        AppLogger.d(TAG, "Response file null/missing, checking expected path: " + expected.getAbsolutePath());
                        if (expected.exists()) {
                            AppLogger.i(TAG, "Found file at expected path, size: " + expected.length() + " bytes");
                            callback.onSuccess(expected.getAbsolutePath());
                        } else {
                            // List files in output dir for debugging
                            String[] files = outputDir.list();
                            AppLogger.e(TAG, "Video file not found at: " + expected.getAbsolutePath());
                            if (files != null) {
                                AppLogger.d(TAG, "Files in output dir (" + files.length + "): " + String.join(", ", files));
                            } else {
                                AppLogger.d(TAG, "Output dir listing returned null");
                            }
                            callback.onError("Video file not found at: " + expected.getAbsolutePath());
                        }
                    }
                } else {
                    String error = downloadResponse.error() != null ? downloadResponse.error().getMessage() : "Download failed";
                    AppLogger.e(TAG, "Download response error: " + error);
                    if (downloadResponse.error() != null) {
                        AppLogger.e(TAG, "Download error details:", downloadResponse.error());
                    }
                    callback.onError("Download error: " + error);
                }

            } catch (Exception e) {
                AppLogger.e(TAG, "Exception during download", e);
                callback.onError("Download exception: " + e.getMessage());
            }
        }).start();
    }

    private Format findFormatByItag(com.github.kiulian.downloader.model.videos.VideoInfo videoInfo, String itag) {
        int itagId = Integer.parseInt(itag);

        for (VideoFormat format : videoInfo.videoFormats()) {
            if (format.itag().id() == itagId) {
                return format;
            }
        }

        for (VideoWithAudioFormat format : videoInfo.videoWithAudioFormats()) {
            if (format.itag().id() == itagId) {
                return format;
            }
        }

        for (AudioFormat format : videoInfo.audioFormats()) {
            if (format.itag().id() == itagId) {
                return format;
            }
        }

        return null;
    }

    public String getBestVideoItag(VideoInfo videoInfo) {
        List<VideoInfo.FormatOption> formats = videoInfo.getVideoFormats();
        if (formats == null || formats.isEmpty()) return null;

        // Find highest quality video-only format
        for (VideoInfo.FormatOption format : formats) {
            if (!format.hasAudio()) {
                return format.getItag();
            }
        }
        return formats.get(0).getItag();
    }

    public String getBestAudioItag(VideoInfo videoInfo) {
        List<VideoInfo.FormatOption> formats = videoInfo.getAudioFormats();
        if (formats == null || formats.isEmpty()) return null;
        return formats.get(0).getItag();
    }
}
