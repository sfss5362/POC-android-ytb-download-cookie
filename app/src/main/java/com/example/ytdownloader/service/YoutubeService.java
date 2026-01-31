package com.example.ytdownloader.service;

import android.content.Context;

import com.example.ytdownloader.manager.AppLogger;
import com.example.ytdownloader.manager.CookieStorage;
import com.example.ytdownloader.model.VideoInfo;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YoutubeService {
    private static final String TAG = "YoutubeService";
    private static final String BOT_DETECTION_MESSAGE = "Sign in to confirm you're not a bot";
    private static final String LOGIN_REQUIRED = "LOGIN_REQUIRED";

    private final Context context;
    private final CookieStorage cookieStorage;

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
    }

    public void refreshDownloader() {
        // No-op: yt-dlp picks up cookies per-request
    }

    private String getCookieFilePath() {
        String cookies = cookieStorage.getCookies();
        if (cookies == null || cookies.isEmpty()) return null;
        try {
            File cookieFile = new File(context.getCacheDir(), "cookies.txt");
            String cookiesTxt = cookieStorage.convertToCookiesTxt(cookies);
            try (FileWriter writer = new FileWriter(cookieFile)) {
                writer.write(cookiesTxt);
            }
            return cookieFile.getAbsolutePath();
        } catch (IOException e) {
            AppLogger.e(TAG, "Failed to create cookie file", e);
            return null;
        }
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
                AppLogger.i(TAG, "Parsing video: " + videoId);
                String videoUrl = "https://www.youtube.com/watch?v=" + videoId;

                YoutubeDLRequest request = new YoutubeDLRequest(videoUrl);
                request.addOption("--dump-json");
                request.addOption("--no-download");
                // 不指定 player_client，让 yt-dlp 使用默认策略（自动选择最优客户端组合）
                request.addOption("--no-playlist");
                request.addOption("--no-check-certificates");

                String cookieFile = getCookieFilePath();
                if (cookieFile != null) {
                    request.addOption("--cookies", cookieFile);
                    AppLogger.d(TAG, "Using cookies file");
                }

                AppLogger.d(TAG, "yt-dlp options: --dump-json --no-download --no-playlist --no-check-certificates"
                        + (cookieFile != null ? " --cookies <file>" : ""));

                com.yausername.youtubedl_android.YoutubeDLResponse response =
                        YoutubeDL.getInstance().execute(request);

                String jsonOutput = response.getOut();
                String errOutput = response.getErr();
                AppLogger.d(TAG, "yt-dlp stdout length: " + (jsonOutput != null ? jsonOutput.length() : 0));
                if (errOutput != null && !errOutput.isEmpty()) {
                    // stderr 可能包含警告、player client 回退等重要信息
                    AppLogger.w(TAG, "yt-dlp stderr:\n" + errOutput);
                }

                if (jsonOutput == null || jsonOutput.isEmpty()) {
                    callback.onError("No response from yt-dlp");
                    return;
                }

                JSONObject json = new JSONObject(jsonOutput);

                // Save info JSON for download reuse (skip re-parsing)
                File infoDir = new File(context.getCacheDir(), "ytdlp_info");
                if (!infoDir.exists()) infoDir.mkdirs();
                File infoFile = new File(infoDir, videoId + ".info.json");
                try (FileWriter fw = new FileWriter(infoFile)) {
                    fw.write(jsonOutput);
                    AppLogger.d(TAG, "Saved info JSON: " + infoFile.getAbsolutePath());
                } catch (IOException e) {
                    AppLogger.w(TAG, "Failed to cache info JSON: " + e.getMessage());
                }

                String title = json.optString("title", "Unknown");
                String author = json.optString("uploader", "Unknown");
                long duration = json.optLong("duration", 0);

                // Thumbnail
                String thumbnail = json.optString("thumbnail", null);
                List<String> thumbnailUrls = new ArrayList<>();
                JSONArray thumbArray = json.optJSONArray("thumbnails");
                if (thumbArray != null) {
                    for (int i = 0; i < thumbArray.length(); i++) {
                        JSONObject t = thumbArray.optJSONObject(i);
                        if (t != null && t.has("url")) {
                            thumbnailUrls.add(t.getString("url"));
                        }
                    }
                }
                if (thumbnail == null && !thumbnailUrls.isEmpty()) {
                    thumbnail = thumbnailUrls.get(thumbnailUrls.size() - 1);
                }

                VideoInfo videoInfo = new VideoInfo(videoId, title, author, thumbnail, duration);
                videoInfo.setThumbnailUrls(thumbnailUrls);

                // Parse formats
                JSONArray formats = json.optJSONArray("formats");
                if (formats == null) {
                    callback.onError("No formats found");
                    return;
                }

                AppLogger.i(TAG, "yt-dlp returned " + formats.length() + " raw formats");

                // Video dedup: key = resolution number
                Map<Integer, VideoInfo.FormatOption> videoDedup = new HashMap<>();
                // Audio dedup: key = abr (approx bitrate)
                Map<Integer, VideoInfo.FormatOption> audioDedup = new HashMap<>();

                int skippedProtocol = 0, skippedNoCodec = 0, skippedNoRes = 0, skippedNoAbr = 0;

                for (int i = 0; i < formats.length(); i++) {
                    JSONObject fmt = formats.getJSONObject(i);

                    String protocol = fmt.optString("protocol", "");
                    String formatId = fmt.optString("format_id", "");
                    String ext = fmt.optString("ext", "");
                    String vcodec = fmt.optString("vcodec", "none");
                    String acodec = fmt.optString("acodec", "none");
                    long filesize = fmt.optLong("filesize", fmt.optLong("filesize_approx", 0));
                    String formatNote = fmt.optString("format_note", "");
                    int height = fmt.optInt("height", 0);

                    boolean hasVideo = !"none".equals(vcodec);
                    boolean hasAudio = !"none".equals(acodec);

                    // 记录每个格式的详细信息
                    AppLogger.d(TAG, String.format("Format[%d]: id=%s ext=%s protocol=%s vcodec=%s acodec=%s height=%d note=%s size=%d",
                            i, formatId, ext, protocol, vcodec, acodec, height, formatNote, filesize));

                    if (protocol.contains("m3u8") || protocol.contains("dash_frag")) {
                        skippedProtocol++;
                        continue;
                    }

                    if (!hasVideo && !hasAudio) {
                        skippedNoCodec++;
                        continue;
                    }

                    if (hasVideo) {
                        // Video format
                        int res = height > 0 ? height : parseResolution(formatNote);
                        if (res <= 0) {
                            skippedNoRes++;
                            AppLogger.d(TAG, "  -> skipped video: no resolution (height=" + height + ", note=" + formatNote + ")");
                            continue;
                        }

                        String quality = res + "p";
                        if (hasAudio) {
                            quality += " (with audio)";
                        }

                        boolean isMp4 = "mp4".equals(ext) || "m4a".equals(ext);
                        boolean exists = videoDedup.containsKey(res);

                        // Prefer: muxed > video-only, mp4 > other
                        if (!exists) {
                            videoDedup.put(res, new VideoInfo.FormatOption(
                                    formatId, quality, ext, ext, filesize, hasAudio, true));
                            AppLogger.d(TAG, "  -> added video: " + quality + " (id=" + formatId + ")");
                        } else {
                            VideoInfo.FormatOption existing = videoDedup.get(res);
                            boolean existingMuxed = existing.hasAudio();
                            boolean existingMp4 = "mp4".equals(existing.getExt());

                            if ((hasAudio && !existingMuxed) ||
                                (hasAudio == existingMuxed && isMp4 && !existingMp4)) {
                                videoDedup.put(res, new VideoInfo.FormatOption(
                                        formatId, quality, ext, ext, filesize, hasAudio, true));
                                AppLogger.d(TAG, "  -> replaced video: " + quality + " (id=" + formatId + ")");
                            } else {
                                AppLogger.d(TAG, "  -> dedup skipped video: " + res + "p (id=" + formatId + ", existing=" + existing.getFormatId() + ")");
                            }
                        }
                    } else if (hasAudio) {
                        // Audio-only format
                        int abr = (int) fmt.optDouble("abr", 0);
                        if (abr <= 0) {
                            int tbr = (int) fmt.optDouble("tbr", 0);
                            abr = tbr > 0 ? tbr : 0;
                        }
                        if (abr <= 0) {
                            skippedNoAbr++;
                            AppLogger.d(TAG, "  -> skipped audio: no bitrate (id=" + formatId + ")");
                            continue;
                        }

                        String quality = abr + "kbps";
                        boolean isMp4 = "m4a".equals(ext) || "mp4".equals(ext);
                        boolean exists = audioDedup.containsKey(abr);

                        if (!exists || (isMp4 && !"m4a".equals(audioDedup.get(abr).getExt()))) {
                            audioDedup.put(abr, new VideoInfo.FormatOption(
                                    formatId, quality, ext, ext, filesize, true, false));
                            AppLogger.d(TAG, "  -> added audio: " + quality + " (id=" + formatId + ")");
                        }
                    }
                }

                AppLogger.i(TAG, String.format("Format filter stats: total=%d, skippedProtocol=%d, skippedNoCodec=%d, skippedNoRes=%d, skippedNoAbr=%d",
                        formats.length(), skippedProtocol, skippedNoCodec, skippedNoRes, skippedNoAbr));

                // Sort video by resolution ascending
                List<Integer> sortedRes = new ArrayList<>(videoDedup.keySet());
                Collections.sort(sortedRes);
                List<VideoInfo.FormatOption> videoFormatList = new ArrayList<>();
                for (int r : sortedRes) {
                    videoFormatList.add(videoDedup.get(r));
                }
                videoInfo.setVideoFormats(videoFormatList);

                // Sort audio by bitrate ascending
                List<Integer> sortedAbr = new ArrayList<>(audioDedup.keySet());
                Collections.sort(sortedAbr);
                List<VideoInfo.FormatOption> audioFormatList = new ArrayList<>();
                for (int a : sortedAbr) {
                    audioFormatList.add(audioDedup.get(a));
                }
                videoInfo.setAudioFormats(audioFormatList);

                AppLogger.i(TAG, "Parse success: " + title + " - " + videoFormatList.size() + " video, " + audioFormatList.size() + " audio formats");
                callback.onSuccess(videoInfo);

            } catch (Exception e) {
                String message = e.getMessage();
                AppLogger.e(TAG, "Exception parsing video: " + (message != null ? message : e.getClass().getName()), e);
                if (message != null && (message.contains(BOT_DETECTION_MESSAGE) || message.contains(LOGIN_REQUIRED))) {
                    AppLogger.w(TAG, "Bot detection triggered");
                    callback.onBotDetected();
                } else {
                    callback.onError(message != null ? message : "Unknown error");
                }
            }
        }).start();
    }

    /**
     * Download using yt-dlp. Returns the process ID for cancellation.
     */
    public String downloadWithYtDlp(String videoId, String formatSpec, String outputPath,
                                     DownloadCallback callback) {
        String processId = UUID.randomUUID().toString();
        new Thread(() -> {
            try {
                String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
                AppLogger.i(TAG, "yt-dlp download: videoId=" + videoId + ", format=" + formatSpec);

                YoutubeDLRequest request = new YoutubeDLRequest(videoUrl);

                // Reuse cached info JSON to skip re-parsing
                File infoFile = new File(new File(context.getCacheDir(), "ytdlp_info"), videoId + ".info.json");
                if (infoFile.exists()) {
                    request.addOption("--load-info-json", infoFile.getAbsolutePath());
                    AppLogger.i(TAG, "Using cached info JSON (skip re-parse)");
                }

                request.addOption("-f", formatSpec);
                request.addOption("-o", outputPath);
                request.addOption("--merge-output-format", "mp4");
                request.addOption("--no-playlist");
                request.addOption("--no-check-certificates");

                String cookieFile = getCookieFilePath();
                if (cookieFile != null) {
                    request.addOption("--cookies", cookieFile);
                }

                final long[] cachedTotal = {0};

                com.yausername.youtubedl_android.YoutubeDLResponse dlResponse =
                    YoutubeDL.getInstance().execute(request, processId, (progress, etaInSeconds, line) -> {
                        if (line != null && !line.isEmpty()) {
                            AppLogger.d(TAG, line);
                            long parsed = parseTotalBytes(line);
                            if (parsed > 0) cachedTotal[0] = parsed;
                        }
                        int pct = progress.intValue();
                        if (pct >= 0) {
                            long downloaded = cachedTotal[0] > 0 ? (long)(cachedTotal[0] * pct / 100.0) : 0;
                            callback.onProgress(pct, downloaded, cachedTotal[0]);
                        }
                        return kotlin.Unit.INSTANCE;
                    });

                // 输出 stderr 便于调试
                String err = dlResponse.getErr();
                if (err != null && !err.isEmpty()) {
                    AppLogger.w(TAG, "yt-dlp stderr:\n" + err);
                }

                // Find the output file - yt-dlp may change extension
                File outFile = findOutputFile(outputPath);
                if (outFile != null && outFile.exists()) {
                    AppLogger.i(TAG, "Download complete: " + outFile.getAbsolutePath() + " (" + outFile.length() + " bytes)");
                    callback.onSuccess(outFile.getAbsolutePath());
                } else {
                    callback.onError("Output file not found after download");
                }

            } catch (Exception e) {
                AppLogger.e(TAG, "yt-dlp download error", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : "Download failed");
            }
        }).start();
        return processId;
    }

    /**
     * Find the actual output file. yt-dlp may substitute %(ext)s with the real extension.
     */
    private File findOutputFile(String outputTemplate) {
        // Try exact path first
        File exact = new File(outputTemplate);
        if (exact.exists()) return exact;

        // If template contains %(ext)s, try common extensions
        if (outputTemplate.contains("%(ext)s")) {
            String[] exts = {"mp4", "mkv", "webm", "m4a", "mp3", "ogg", "opus"};
            for (String ext : exts) {
                File f = new File(outputTemplate.replace("%(ext)s", ext));
                if (f.exists()) return f;
            }
        }

        // Try the directory listing for files with the base name
        File parent = exact.getParentFile();
        String baseName = exact.getName();
        // Strip extension/template from name
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            baseName = baseName.substring(0, dot);
        }
        baseName = baseName.replace("%(ext)s", "");

        if (parent != null && parent.exists()) {
            String finalBase = baseName;
            File[] matches = parent.listFiles((dir, name) -> name.startsWith(finalBase));
            if (matches != null && matches.length > 0) {
                return matches[0];
            }
        }

        return null;
    }

    public void cancelDownload(String processId) {
        if (processId != null) {
            YoutubeDL.getInstance().destroyProcessById(processId);
            AppLogger.i(TAG, "Cancelled download process: " + processId);
        }
    }

    private static int parseResolution(String qualityLabel) {
        if (qualityLabel == null) return 0;
        Matcher m = Pattern.compile("(\\d+)p").matcher(qualityLabel);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * Parse total bytes from yt-dlp output line like:
     * "[download]  45.3% of 125.50MiB at 10.00MiB/s ETA 00:30"
     */
    private static long parseTotalBytes(String line) {
        if (line == null) return 0;
        Matcher m = Pattern.compile("of\\s+~?\\s*(\\d+\\.?\\d*)\\s*(KiB|MiB|GiB)").matcher(line);
        if (m.find()) {
            double val = Double.parseDouble(m.group(1));
            switch (m.group(2)) {
                case "KiB": return (long) (val * 1024);
                case "MiB": return (long) (val * 1024 * 1024);
                case "GiB": return (long) (val * 1024 * 1024 * 1024);
            }
        }
        return 0;
    }
}
