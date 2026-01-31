package com.example.ytdownloader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.example.ytdownloader.MainActivity;
import com.example.ytdownloader.R;
import com.example.ytdownloader.manager.AppLogger;
import com.example.ytdownloader.model.DownloadTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DownloadService extends Service {
    private static final String TAG = "DownloadService";
    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private final ConcurrentHashMap<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SegmentedDownloader> activeDownloaders = new ConcurrentHashMap<>();
    private final List<DownloadListener> listeners = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build();

    public interface DownloadListener {
        void onTaskAdded(DownloadTask task);
        void onTaskUpdated(DownloadTask task);
        void onTaskCompleted(DownloadTask task);
        void onTaskFailed(DownloadTask task);
    }

    public class LocalBinder extends Binder {
        public DownloadService getService() {
            return DownloadService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification("Download service running"));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void addListener(DownloadListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(DownloadListener listener) {
        listeners.remove(listener);
    }

    public boolean hasActiveDownload(String videoId) {
        for (DownloadTask task : tasks.values()) {
            if (task.getVideoId().equals(videoId)
                    && (task.getStatus() == DownloadTask.Status.PENDING
                        || task.getStatus() == DownloadTask.Status.DOWNLOADING)) {
                return true;
            }
        }
        return false;
    }

    public List<DownloadTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public void removeTask(String taskId) {
        tasks.remove(taskId);
    }

    public void pauseTask(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) return;
        if (task.getStatus() != DownloadTask.Status.DOWNLOADING
                && task.getStatus() != DownloadTask.Status.PENDING) return;
        // Cancel active downloader (saves .seg metadata for resume)
        SegmentedDownloader dl = activeDownloaders.get(taskId);
        if (dl != null) {
            dl.cancel();
            activeDownloaders.remove(taskId);
        }
        task.setStatus(DownloadTask.Status.PAUSED);
        notifyTaskUpdated(task);
        AppLogger.i(TAG, "Paused: " + task.getTitle());
    }

    public void resumeTask(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) return;
        if (task.getStatus() != DownloadTask.Status.PAUSED
                && task.getStatus() != DownloadTask.Status.FAILED) return;
        task.setStatus(DownloadTask.Status.PENDING);
        task.setErrorMessage(null);
        // Don't reset downloadedBytes/progress — SegmentedDownloader resumes from .seg
        notifyTaskUpdated(task);
        startDownload(task);
        AppLogger.i(TAG, "Resumed: " + task.getTitle());
    }

    public void cancelTask(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) return;
        // Cancel active downloader
        SegmentedDownloader dl = activeDownloaders.get(taskId);
        if (dl != null) {
            dl.cancel();
            dl.deleteMetadata();
            activeDownloaders.remove(taskId);
        }
        // Clean up partial files and .seg metadata
        if (task.getCachePath() != null) {
            File partial = new File(task.getCachePath());
            if (partial.exists()) partial.delete();
            // Also clean up .seg files
            File seg = new File(task.getCachePath() + ".seg");
            if (seg.exists()) seg.delete();
            File segTmp = new File(task.getCachePath() + ".seg.tmp");
            if (segTmp.exists()) segTmp.delete();
        }
        task.setStatus(DownloadTask.Status.CANCELLED);
        notifyTaskUpdated(task);
        AppLogger.i(TAG, "Cancelled: " + task.getTitle());
    }

    public String createTask(String videoId, String title, String thumbnailUrl,
                             DownloadTask.DownloadType type, String downloadUrl,
                             String audioDownloadUrl) {
        String taskId = UUID.randomUUID().toString();
        DownloadTask task = new DownloadTask(taskId, videoId, title, thumbnailUrl, type);
        task.setDownloadUrl(downloadUrl);
        task.setAudioDownloadUrl(audioDownloadUrl);
        tasks.put(taskId, task);

        for (DownloadListener listener : listeners) {
            listener.onTaskAdded(task);
        }

        startDownload(task);
        return taskId;
    }

    public String createThumbnailTask(String videoId, String title, String thumbnailUrl, String downloadUrl) {
        String taskId = UUID.randomUUID().toString();
        DownloadTask task = new DownloadTask(taskId, videoId, title, thumbnailUrl, DownloadTask.DownloadType.THUMBNAIL);
        task.setDownloadUrl(downloadUrl);
        tasks.put(taskId, task);

        for (DownloadListener listener : listeners) {
            listener.onTaskAdded(task);
        }

        startDownload(task);
        return taskId;
    }

    private void startDownload(DownloadTask task) {
        String safeTitle = task.getTitle().replaceAll("[^a-zA-Z0-9\\s]", "").trim();
        if (safeTitle.length() > 50) {
            safeTitle = safeTitle.substring(0, 50);
        }
        AppLogger.i(TAG, "startDownload: type=" + task.getDownloadType() + ", title=" + safeTitle);

        switch (task.getDownloadType()) {
            case VIDEO:
            case AUDIO:
                downloadStream(task, safeTitle);
                break;
            case THUMBNAIL:
                downloadThumbnail(task, safeTitle);
                break;
        }
    }

    private void downloadStream(DownloadTask task, String filename) {
        task.setStatus(DownloadTask.Status.DOWNLOADING);
        notifyTaskUpdated(task);
        updateNotification("Downloading: " + task.getTitle());

        File cacheDir = new File(getCacheDir(), "downloads");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        new Thread(() -> {
            try {
                String filePath;
                boolean needsMerge = task.getAudioDownloadUrl() != null
                        && !task.getAudioDownloadUrl().isEmpty();

                if (needsMerge) {
                    filePath = downloadAndMerge(task, filename, cacheDir);
                } else {
                    String ext = task.getDownloadType() == DownloadTask.DownloadType.AUDIO ? "m4a" : "mp4";
                    File outputFile = new File(cacheDir, filename + "." + ext);
                    task.setCachePath(outputFile.getAbsolutePath());
                    downloadFile(task, task.getDownloadUrl(), outputFile, 0);
                    filePath = outputFile.getAbsolutePath();
                }

                if (task.getStatus() == DownloadTask.Status.DOWNLOADING) {
                    moveToMoviesAndComplete(task, filePath);
                }
            } catch (Exception e) {
                AppLogger.e(TAG, "Download failed: " + e.getMessage(), e);
                if (task.getStatus() == DownloadTask.Status.DOWNLOADING) {
                    task.setErrorMessage(e.getMessage());
                    task.setStatus(DownloadTask.Status.FAILED);
                    notifyTaskFailed(task);
                }
            }
        }).start();
    }

    /**
     * Download a single file via SegmentedDownloader with multi-segment parallel download.
     */
    private void downloadFile(DownloadTask task, String url, File outputFile, long baseOffset) throws Exception {
        SegmentedDownloader dl = new SegmentedDownloader(httpClient, outputFile,
                (downloadedBytes, singleTotalBytes) -> {
                    // For segmented downloads, singleTotalBytes is 0 (total already set)
                    // For single-connection fallback, singleTotalBytes is the content length
                    if (task.getTotalBytes() == 0 && singleTotalBytes > 0) {
                        task.setTotalBytes(singleTotalBytes);
                    }
                    task.setDownloadedBytes(downloadedBytes);
                    if (task.getTotalBytes() > 0) {
                        task.setProgress((int) (downloadedBytes * 100 / task.getTotalBytes()));
                    }
                    notifyTaskUpdated(task);
                });
        activeDownloaders.put(task.getId(), dl);
        try {
            dl.download(url, baseOffset);
        } finally {
            activeDownloaders.remove(task.getId());
        }
    }

    /**
     * Download video + audio separately, then merge with MediaMuxer.
     * Probes content-length of both streams upfront for accurate total progress.
     */
    private String downloadAndMerge(DownloadTask task, String filename, File cacheDir) throws Exception {
        File videoFile = new File(cacheDir, filename + "_v.mp4");
        File audioFile = new File(cacheDir, filename + "_a.m4a");
        File mergedFile = new File(cacheDir, filename + ".mp4");
        task.setCachePath(mergedFile.getAbsolutePath());

        // Probe content-length for both streams to set accurate total
        SegmentedDownloader probe = new SegmentedDownloader(httpClient, videoFile, null);
        long videoSize = probe.probeContentLength(task.getDownloadUrl());
        long audioSize = probe.probeContentLength(task.getAudioDownloadUrl());
        if (videoSize > 0 && audioSize > 0) {
            task.setTotalBytes(videoSize + audioSize);
            AppLogger.i(TAG, "Total size: video=" + videoSize + " + audio=" + audioSize
                    + " = " + (videoSize + audioSize));
        }

        // Phase 1: Download video stream (baseOffset=0)
        AppLogger.i(TAG, "Downloading video stream...");
        downloadFile(task, task.getDownloadUrl(), videoFile, 0);

        if (task.getStatus() != DownloadTask.Status.DOWNLOADING) return mergedFile.getAbsolutePath();

        // Phase 2: Download audio stream (baseOffset=videoSize for continuous progress)
        AppLogger.i(TAG, "Downloading audio stream...");
        downloadFile(task, task.getAudioDownloadUrl(), audioFile, Math.max(videoSize, 0));

        if (task.getStatus() != DownloadTask.Status.DOWNLOADING) return mergedFile.getAbsolutePath();

        // Phase 3: Mux video + audio
        AppLogger.i(TAG, "Muxing video + audio...");
        task.setProgress(95);
        notifyTaskUpdated(task);

        muxVideoAudio(videoFile.getAbsolutePath(), audioFile.getAbsolutePath(), mergedFile.getAbsolutePath());

        // Clean up temp files
        videoFile.delete();
        audioFile.delete();

        AppLogger.i(TAG, "Merge complete: " + mergedFile.getAbsolutePath());
        return mergedFile.getAbsolutePath();
    }

    /**
     * Combine video-only and audio-only streams into a single mp4 using MediaMuxer.
     */
    private void muxVideoAudio(String videoPath, String audioPath, String outputPath) throws Exception {
        MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Video track
        MediaExtractor videoExtractor = new MediaExtractor();
        videoExtractor.setDataSource(videoPath);
        int muxerVideoTrack = -1;
        for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
            MediaFormat format = videoExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                videoExtractor.selectTrack(i);
                muxerVideoTrack = muxer.addTrack(format);
                break;
            }
        }

        // Audio track
        MediaExtractor audioExtractor = new MediaExtractor();
        audioExtractor.setDataSource(audioPath);
        int muxerAudioTrack = -1;
        for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
            MediaFormat format = audioExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioExtractor.selectTrack(i);
                muxerAudioTrack = muxer.addTrack(format);
                break;
            }
        }

        if (muxerVideoTrack < 0 || muxerAudioTrack < 0) {
            videoExtractor.release();
            audioExtractor.release();
            muxer.release();
            throw new Exception("Failed to find video/audio tracks for muxing");
        }

        muxer.start();

        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        // Write video samples
        while (true) {
            int sampleSize = videoExtractor.readSampleData(buffer, 0);
            if (sampleSize < 0) break;
            bufferInfo.offset = 0;
            bufferInfo.size = sampleSize;
            bufferInfo.presentationTimeUs = videoExtractor.getSampleTime();
            bufferInfo.flags = videoExtractor.getSampleFlags();
            muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo);
            videoExtractor.advance();
        }

        // Write audio samples
        while (true) {
            int sampleSize = audioExtractor.readSampleData(buffer, 0);
            if (sampleSize < 0) break;
            bufferInfo.offset = 0;
            bufferInfo.size = sampleSize;
            bufferInfo.presentationTimeUs = audioExtractor.getSampleTime();
            bufferInfo.flags = audioExtractor.getSampleFlags();
            muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo);
            audioExtractor.advance();
        }

        muxer.stop();
        muxer.release();
        videoExtractor.release();
        audioExtractor.release();
    }

    private void moveToMoviesAndComplete(DownloadTask task, String filePath) {
        File srcFile = new File(filePath);
        if (!srcFile.exists()) {
            AppLogger.e(TAG, "Source file not found: " + filePath);
            task.setOutputPath(filePath);
            completeTask(task);
            return;
        }

        File moviesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "YTDownloader");
        if (!moviesDir.exists()) {
            moviesDir.mkdirs();
        }

        File destFile = new File(moviesDir, srcFile.getName());
        if (destFile.exists()) {
            String name = srcFile.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            int n = 1;
            while (destFile.exists()) {
                destFile = new File(moviesDir, base + "_" + n + ext);
                n++;
            }
        }

        boolean moved = srcFile.renameTo(destFile);
        if (!moved) {
            try {
                java.io.InputStream in = new java.io.FileInputStream(srcFile);
                java.io.OutputStream out = new java.io.FileOutputStream(destFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                out.close();
                srcFile.delete();
                moved = true;
            } catch (Exception e) {
                AppLogger.e(TAG, "Failed to copy file to Movies", e);
            }
        }

        if (moved) {
            AppLogger.i(TAG, "Moved to gallery: " + destFile.getAbsolutePath());
            task.setOutputPath(destFile.getAbsolutePath());
            MediaScannerConnection.scanFile(this,
                    new String[]{destFile.getAbsolutePath()}, null, null);
        } else {
            AppLogger.w(TAG, "Move failed, keeping original: " + filePath);
            task.setOutputPath(filePath);
        }
        completeTask(task);
    }

    private void downloadThumbnail(DownloadTask task, String safeTitle) {
        task.setStatus(DownloadTask.Status.DOWNLOADING);
        notifyTaskUpdated(task);
        updateNotification("Downloading cover: " + task.getTitle());

        new Thread(() -> {
            try {
                String url = task.getDownloadUrl();
                Request request = new Request.Builder().url(url).build();
                Response response = httpClient.newCall(request).execute();
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    task.setErrorMessage("HTTP error: " + response.code());
                    task.setStatus(DownloadTask.Status.FAILED);
                    notifyTaskFailed(task);
                    return;
                }

                String ext = ".jpg";
                String contentType = body.contentType() != null ? body.contentType().toString() : "";
                if (contentType.contains("png")) ext = ".png";
                else if (contentType.contains("webp")) ext = ".webp";

                File cacheDir = new File(getCacheDir(), "downloads");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File cacheFile = new File(cacheDir, safeTitle + "_cover" + ext);

                InputStream in = body.byteStream();
                FileOutputStream out = new FileOutputStream(cacheFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                out.close();

                AppLogger.i(TAG, "Cover cached: " + cacheFile.getAbsolutePath());
                moveToMoviesAndComplete(task, cacheFile.getAbsolutePath());
            } catch (Exception e) {
                AppLogger.e(TAG, "Cover download failed", e);
                task.setErrorMessage(e.getMessage());
                task.setStatus(DownloadTask.Status.FAILED);
                notifyTaskFailed(task);
            }
        }).start();
    }

    private void completeTask(DownloadTask task) {
        task.setStatus(DownloadTask.Status.COMPLETED);
        task.setProgress(100);
        notifyTaskCompleted(task);
        updateNotification("Completed: " + task.getTitle());
    }

    private void notifyTaskUpdated(DownloadTask task) {
        for (DownloadListener listener : listeners) {
            listener.onTaskUpdated(task);
        }
    }

    private void notifyTaskCompleted(DownloadTask task) {
        for (DownloadListener listener : listeners) {
            listener.onTaskCompleted(task);
        }
    }

    private void notifyTaskFailed(DownloadTask task) {
        for (DownloadListener listener : listeners) {
            listener.onTaskFailed(task);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, createNotification(text));
    }
}
