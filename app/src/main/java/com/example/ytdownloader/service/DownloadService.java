package com.example.ytdownloader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.example.ytdownloader.MainActivity;
import com.example.ytdownloader.R;
import com.example.ytdownloader.manager.AppLogger;
import com.example.ytdownloader.manager.FFmpegHelper;
import com.example.ytdownloader.model.DownloadTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

public class DownloadService extends Service {
    private static final String TAG = "DownloadService";
    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private YoutubeService youtubeService;
    private final ConcurrentHashMap<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final List<DownloadListener> listeners = new ArrayList<>();

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
        youtubeService = new YoutubeService(this);
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

    public void refreshYoutubeService() {
        youtubeService.refreshDownloader();
    }

    public List<DownloadTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public void removeTask(String taskId) {
        tasks.remove(taskId);
    }

    public String createTask(String videoId, String title, String thumbnailUrl, DownloadTask.DownloadType type,
                             String videoItag, String audioItag) {
        String taskId = UUID.randomUUID().toString();
        DownloadTask task = new DownloadTask(taskId, videoId, title, thumbnailUrl, type);
        task.setVideoItag(videoItag);
        task.setAudioItag(audioItag);
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
        File cacheDir = getCacheDir();
        File downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YTDownloader");
        if (!downloadDir.exists()) {
            boolean created = downloadDir.mkdirs();
            AppLogger.d(TAG, "Download dir created: " + created + " - " + downloadDir.getAbsolutePath());
        }
        AppLogger.d(TAG, "Download dir: " + downloadDir.getAbsolutePath() + " writable: " + downloadDir.canWrite());

        String safeTitle = task.getTitle().replaceAll("[^a-zA-Z0-9\\s]", "").trim();
        if (safeTitle.length() > 50) {
            safeTitle = safeTitle.substring(0, 50);
        }
        AppLogger.i(TAG, "startDownload: type=" + task.getDownloadType() + ", videoItag=" + task.getVideoItag() + ", audioItag=" + task.getAudioItag() + ", title=" + safeTitle);

        switch (task.getDownloadType()) {
            case VIDEO_ONLY:
                downloadVideoOnly(task, downloadDir, safeTitle);
                break;
            case AUDIO_ONLY:
                downloadAudioOnly(task, downloadDir, safeTitle);
                break;
            case BEST_QUALITY_MERGE:
                downloadAndMerge(task, cacheDir, downloadDir, safeTitle);
                break;
            case THUMBNAIL:
                downloadThumbnail(task, safeTitle);
                break;
        }
    }

    private void downloadVideoOnly(DownloadTask task, File outputDir, String filename) {
        task.setStatus(DownloadTask.Status.DOWNLOADING_VIDEO);
        notifyTaskUpdated(task);
        updateNotification("Downloading: " + task.getTitle());

        youtubeService.downloadFormat(task.getVideoId(), task.getVideoItag(), outputDir, filename,
                new YoutubeService.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        task.setProgress(progress);
                        notifyTaskUpdated(task);
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        moveToMoviesAndComplete(task, filePath);
                    }

                    @Override
                    public void onError(String error) {
                        task.setErrorMessage(error);
                        task.setStatus(DownloadTask.Status.FAILED);
                        notifyTaskFailed(task);
                    }
                });
    }

    private void downloadAudioOnly(DownloadTask task, File outputDir, String filename) {
        task.setStatus(DownloadTask.Status.DOWNLOADING_AUDIO);
        notifyTaskUpdated(task);
        updateNotification("Downloading: " + task.getTitle());

        youtubeService.downloadFormat(task.getVideoId(), task.getAudioItag(), outputDir, filename,
                new YoutubeService.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        task.setProgress(progress);
                        notifyTaskUpdated(task);
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        moveToMoviesAndComplete(task, filePath);
                    }

                    @Override
                    public void onError(String error) {
                        task.setErrorMessage(error);
                        task.setStatus(DownloadTask.Status.FAILED);
                        notifyTaskFailed(task);
                    }
                });
    }

    private void downloadAndMerge(DownloadTask task, File cacheDir, File outputDir, String filename) {
        task.setStatus(DownloadTask.Status.DOWNLOADING_VIDEO);
        notifyTaskUpdated(task);
        updateNotification("Downloading video: " + task.getTitle());

        String outputPath = new File(outputDir, filename + ".mp4").getAbsolutePath();

        // Download video first (no extension - library adds it automatically)
        youtubeService.downloadFormat(task.getVideoId(), task.getVideoItag(), cacheDir, task.getId() + "_video",
                new YoutubeService.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        task.setProgress(progress / 2); // 0-50% for video
                        notifyTaskUpdated(task);
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        // Video done, filePath is the actual path with correct extension
                        downloadAudioForMerge(task, cacheDir, filePath, outputPath);
                    }

                    @Override
                    public void onError(String error) {
                        task.setErrorMessage(error);
                        task.setStatus(DownloadTask.Status.FAILED);
                        notifyTaskFailed(task);
                    }
                });
    }

    private void downloadThumbnail(DownloadTask task, String safeTitle) {
        task.setStatus(DownloadTask.Status.DOWNLOADING_VIDEO);
        notifyTaskUpdated(task);
        updateNotification("Downloading cover: " + task.getTitle());

        new Thread(() -> {
            try {
                String url = task.getDownloadUrl();
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(url).build();
                okhttp3.Response response = client.newCall(request).execute();
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    task.setErrorMessage("HTTP error: " + response.code());
                    task.setStatus(DownloadTask.Status.FAILED);
                    notifyTaskFailed(task);
                    return;
                }

                File moviesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "YTDownloader");
                if (!moviesDir.exists()) {
                    moviesDir.mkdirs();
                }

                String ext = ".jpg";
                String contentType = body.contentType() != null ? body.contentType().toString() : "";
                if (contentType.contains("png")) ext = ".png";
                else if (contentType.contains("webp")) ext = ".webp";

                File destFile = new File(moviesDir, safeTitle + "_cover" + ext);
                int n = 1;
                while (destFile.exists()) {
                    destFile = new File(moviesDir, safeTitle + "_cover_" + n + ext);
                    n++;
                }

                InputStream in = body.byteStream();
                FileOutputStream out = new FileOutputStream(destFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                out.close();

                AppLogger.i(TAG, "Cover saved: " + destFile.getAbsolutePath());
                task.setOutputPath(destFile.getAbsolutePath());
                MediaScannerConnection.scanFile(this,
                        new String[]{destFile.getAbsolutePath()}, null, null);
                completeTask(task);
            } catch (Exception e) {
                AppLogger.e(TAG, "Cover download failed", e);
                task.setErrorMessage(e.getMessage());
                task.setStatus(DownloadTask.Status.FAILED);
                notifyTaskFailed(task);
            }
        }).start();
    }

    private void downloadAudioForMerge(DownloadTask task, File cacheDir, String videoRealPath, String outputPath) {
        task.setStatus(DownloadTask.Status.DOWNLOADING_AUDIO);
        notifyTaskUpdated(task);
        updateNotification("Downloading audio: " + task.getTitle());

        youtubeService.downloadFormat(task.getVideoId(), task.getAudioItag(), cacheDir, task.getId() + "_audio",
                new YoutubeService.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        task.setProgress(50 + progress / 2); // 50-100% for audio
                        notifyTaskUpdated(task);
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        // Audio done, use actual paths from callbacks
                        mergeFiles(task, videoRealPath, filePath, outputPath);
                    }

                    @Override
                    public void onError(String error) {
                        task.setErrorMessage(error);
                        task.setStatus(DownloadTask.Status.FAILED);
                        notifyTaskFailed(task);
                    }
                });
    }

    private void mergeFiles(DownloadTask task, String videoPath, String audioPath, String outputPath) {
        task.setStatus(DownloadTask.Status.MERGING);
        task.setProgress(100);
        notifyTaskUpdated(task);
        updateNotification("Merging: " + task.getTitle());

        FFmpegHelper.mergeVideoAudio(videoPath, audioPath, outputPath, new FFmpegHelper.MergeCallback() {
            @Override
            public void onProgress(int progress) {
                // FFmpeg progress is hard to track, just show indeterminate
            }

            @Override
            public void onSuccess(String filePath) {
                moveToMoviesAndComplete(task, filePath);
            }

            @Override
            public void onError(String error) {
                task.setErrorMessage(error);
                task.setStatus(DownloadTask.Status.FAILED);
                notifyTaskFailed(task);
            }
        });
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
        // Avoid overwrite: add suffix
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
            // renameTo may fail across mount points, try copy
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
            // Notify media scanner so it shows in gallery
            MediaScannerConnection.scanFile(this,
                    new String[]{destFile.getAbsolutePath()}, null, null);
        } else {
            AppLogger.w(TAG, "Move failed, keeping original: " + filePath);
            task.setOutputPath(filePath);
        }
        completeTask(task);
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
