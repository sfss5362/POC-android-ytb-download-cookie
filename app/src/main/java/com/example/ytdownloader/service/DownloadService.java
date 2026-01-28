package com.example.ytdownloader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.ytdownloader.MainActivity;
import com.example.ytdownloader.R;
import com.example.ytdownloader.manager.FFmpegHelper;
import com.example.ytdownloader.model.DownloadTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private void startDownload(DownloadTask task) {
        File cacheDir = getCacheDir();
        File downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YTDownloader");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }

        String safeTitle = task.getTitle().replaceAll("[^a-zA-Z0-9\\s]", "").trim();
        if (safeTitle.length() > 50) {
            safeTitle = safeTitle.substring(0, 50);
        }

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
        }
    }

    private void downloadVideoOnly(DownloadTask task, File outputDir, String filename) {
        task.setStatus(DownloadTask.Status.DOWNLOADING_VIDEO);
        notifyTaskUpdated(task);
        updateNotification("Downloading: " + task.getTitle());

        youtubeService.downloadFormat(task.getVideoId(), task.getVideoItag(), outputDir, filename + ".mp4",
                new YoutubeService.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        task.setProgress(progress);
                        notifyTaskUpdated(task);
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        task.setOutputPath(filePath);
                        task.setStatus(DownloadTask.Status.COMPLETED);
                        task.setProgress(100);
                        notifyTaskCompleted(task);
                        updateNotification("Completed: " + task.getTitle());
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

        youtubeService.downloadFormat(task.getVideoId(), task.getAudioItag(), outputDir, filename + ".m4a",
                new YoutubeService.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        task.setProgress(progress);
                        notifyTaskUpdated(task);
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        task.setOutputPath(filePath);
                        task.setStatus(DownloadTask.Status.COMPLETED);
                        task.setProgress(100);
                        notifyTaskCompleted(task);
                        updateNotification("Completed: " + task.getTitle());
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

        // Download to output dir so files are kept if merge fails
        String videoPath = new File(outputDir, filename + "_video.mp4").getAbsolutePath();
        String audioPath = new File(outputDir, filename + "_audio.m4a").getAbsolutePath();
        String outputPath = new File(outputDir, filename + ".mp4").getAbsolutePath();

        // Download video first
        youtubeService.downloadFormat(task.getVideoId(), task.getVideoItag(), outputDir, filename + "_video.mp4",
                new YoutubeService.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        task.setProgress(progress / 2); // 0-50% for video
                        notifyTaskUpdated(task);
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        // Video done, download audio
                        downloadAudioForMerge(task, outputDir, audioPath, videoPath, outputPath, filename);
                    }

                    @Override
                    public void onError(String error) {
                        task.setErrorMessage(error);
                        task.setStatus(DownloadTask.Status.FAILED);
                        notifyTaskFailed(task);
                    }
                });
    }

    private void downloadAudioForMerge(DownloadTask task, File outputDir, String audioPath, String videoPath, String outputPath, String filename) {
        task.setStatus(DownloadTask.Status.DOWNLOADING_AUDIO);
        notifyTaskUpdated(task);
        updateNotification("Downloading audio: " + task.getTitle());

        youtubeService.downloadFormat(task.getVideoId(), task.getAudioItag(), outputDir, filename + "_audio.m4a",
                new YoutubeService.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        task.setProgress(50 + progress / 2); // 50-100% for audio
                        notifyTaskUpdated(task);
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        // Audio done, merge
                        mergeFiles(task, videoPath, audioPath, outputPath);
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
                task.setOutputPath(filePath);
                task.setStatus(DownloadTask.Status.COMPLETED);
                notifyTaskCompleted(task);
                updateNotification("Completed: " + task.getTitle());
            }

            @Override
            public void onError(String error) {
                // Merge failed but files are downloaded - mark as completed
                task.setOutputPath(videoPath);
                task.setErrorMessage("Merge skipped: " + error);
                task.setStatus(DownloadTask.Status.COMPLETED);
                notifyTaskCompleted(task);
                updateNotification("Downloaded (no merge): " + task.getTitle());
            }
        });
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
