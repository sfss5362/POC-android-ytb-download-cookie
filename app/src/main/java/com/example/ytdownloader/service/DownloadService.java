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
import com.example.ytdownloader.model.DownloadTask;

import android.os.Handler;
import android.os.Looper;

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
        // Kill yt-dlp process
        if (task.getProcessId() != null) {
            youtubeService.cancelDownload(task.getProcessId());
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
        notifyTaskUpdated(task);
        startDownload(task);
        AppLogger.i(TAG, "Resumed: " + task.getTitle());
    }

    public void cancelTask(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) return;
        // Kill process if running
        if (task.getProcessId() != null) {
            youtubeService.cancelDownload(task.getProcessId());
        }
        // Clean up partial file
        if (task.getCachePath() != null) {
            File partial = new File(task.getCachePath());
            if (partial.exists()) partial.delete();
            // Also try .part file
            File partFile = new File(task.getCachePath() + ".part");
            if (partFile.exists()) partFile.delete();
        }
        task.setStatus(DownloadTask.Status.CANCELLED);
        notifyTaskUpdated(task);
        AppLogger.i(TAG, "Cancelled: " + task.getTitle());
    }

    public String createTask(String videoId, String title, String thumbnailUrl,
                             DownloadTask.DownloadType type, String formatSpec) {
        String taskId = UUID.randomUUID().toString();
        DownloadTask task = new DownloadTask(taskId, videoId, title, thumbnailUrl, type);
        task.setFormatSpec(formatSpec);
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
        AppLogger.i(TAG, "startDownload: type=" + task.getDownloadType() + ", formatSpec=" + task.getFormatSpec() + ", title=" + safeTitle);

        switch (task.getDownloadType()) {
            case VIDEO:
            case AUDIO:
                downloadWithYtDlp(task, safeTitle);
                break;
            case THUMBNAIL:
                downloadThumbnail(task, safeTitle);
                break;
        }
    }

    private void downloadWithYtDlp(DownloadTask task, String filename) {
        task.setStatus(DownloadTask.Status.DOWNLOADING);
        notifyTaskUpdated(task);
        updateNotification("Downloading: " + task.getTitle());

        // yt-dlp 无法直接写入 Movies（Scoped Storage 限制），先下载到缓存目录
        File cacheDir = new File(getCacheDir(), "ytdlp_downloads");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        String outputPath = new File(cacheDir, filename + ".%(ext)s").getAbsolutePath();
        task.setCachePath(outputPath);

        // Start file-size progress poller (updates UI every 500ms)
        Handler pollHandler = new Handler(Looper.getMainLooper());
        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (task.getStatus() != DownloadTask.Status.DOWNLOADING) return;
                // Scan cache dir for matching partial/complete files
                File[] files = cacheDir.listFiles((dir, name) -> name.startsWith(filename));
                if (files != null) {
                    long totalOnDisk = 0;
                    for (File f : files) totalOnDisk += f.length();
                    if (totalOnDisk > 0 && totalOnDisk != task.getDownloadedBytes()) {
                        task.setDownloadedBytes(totalOnDisk);
                        if (task.getTotalBytes() > 0) {
                            task.setProgress((int) (totalOnDisk * 100 / task.getTotalBytes()));
                        }
                        notifyTaskUpdated(task);
                    }
                }
                pollHandler.postDelayed(this, 500);
            }
        };
        pollHandler.postDelayed(pollRunnable, 500);

        String processId = youtubeService.downloadWithYtDlp(
                task.getVideoId(),
                task.getFormatSpec(),
                outputPath,
                new YoutubeService.DownloadCallback() {
                    @Override
                    public void onProgress(int progress, long downloadedBytes, long totalBytes) {
                        task.setProgress(progress);
                        task.setDownloadedBytes(downloadedBytes);
                        task.setTotalBytes(totalBytes);
                        notifyTaskUpdated(task);
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        pollHandler.removeCallbacksAndMessages(null);
                        moveToMoviesAndComplete(task, filePath);
                    }

                    @Override
                    public void onError(String error) {
                        pollHandler.removeCallbacksAndMessages(null);
                        // Only set FAILED if not already paused/cancelled
                        if (task.getStatus() == DownloadTask.Status.DOWNLOADING) {
                            task.setErrorMessage(error);
                            task.setStatus(DownloadTask.Status.FAILED);
                            notifyTaskFailed(task);
                        }
                    }
                });

        task.setProcessId(processId);
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

                String ext = ".jpg";
                String contentType = body.contentType() != null ? body.contentType().toString() : "";
                if (contentType.contains("png")) ext = ".png";
                else if (contentType.contains("webp")) ext = ".webp";

                // 先写缓存目录，再通过 moveToMoviesAndComplete 移到 Movies
                File cacheDir = new File(getCacheDir(), "ytdlp_downloads");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }
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
