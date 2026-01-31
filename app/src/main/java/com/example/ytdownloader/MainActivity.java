package com.example.ytdownloader;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.ytdownloader.adapter.DownloadListAdapter;
import com.example.ytdownloader.manager.AppLogger;
import com.example.ytdownloader.model.DownloadTask;
import com.example.ytdownloader.model.VideoInfo;
import com.example.ytdownloader.service.DownloadService;
import com.example.ytdownloader.service.YoutubeService;
import com.google.android.material.button.MaterialButton;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel;
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus;

import java.util.List;

public class MainActivity extends AppCompatActivity implements DownloadService.DownloadListener, AppLogger.LogListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1001;

    private EditText etUrl;
    private MaterialButton btnParse;
    private ImageButton btnUpdateYtDlp;
    private View cardVideoInfo;
    private ImageView ivThumbnail;
    private TextView tvTitle;
    private TextView tvDuration;
    private LinearLayout llFormats;
    private ProgressBar progressLoading;
    private RecyclerView rvDownloads;
    private TextView tvEmpty;

    private YoutubeService youtubeService;
    private DownloadService downloadService;
    private boolean serviceBound = false;
    private DownloadListAdapter adapter;
    private Handler mainHandler;

    private VideoInfo currentVideoInfo;
    private String pendingVideoId;


    private final ActivityResultLauncher<Intent> webViewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    youtubeService.refreshDownloader();
                    if (downloadService != null) {
                        downloadService.refreshYoutubeService();
                    }
                    appendLog("INFO", "Login successful, cookies saved. Retrying parse...");
                    if (pendingVideoId != null) {
                        parseVideo(pendingVideoId);
                    }
                } else {
                    Toast.makeText(this, "Login cancelled", Toast.LENGTH_SHORT).show();
                    appendLog("WARN", "Login cancelled by user");
                }
            }
    );

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            DownloadService.LocalBinder binder = (DownloadService.LocalBinder) service;
            downloadService = binder.getService();
            downloadService.addListener(MainActivity.this);
            serviceBound = true;

            adapter.setTasks(downloadService.getAllTasks());
            updateEmptyState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());
        youtubeService = new YoutubeService(this);

        AppLogger.addListener(this);

        initViews();
        setupListeners();
        startAndBindService();
        requestNotificationPermission();
        handleShareIntent();
    }

    private void initViews() {
        etUrl = findViewById(R.id.etUrl);
        btnParse = findViewById(R.id.btnParse);
        btnUpdateYtDlp = findViewById(R.id.btnUpdateYtDlp);
        cardVideoInfo = findViewById(R.id.cardVideoInfo);
        ivThumbnail = findViewById(R.id.ivThumbnail);
        tvTitle = findViewById(R.id.tvTitle);
        tvDuration = findViewById(R.id.tvDuration);
        llFormats = findViewById(R.id.llFormats);
        progressLoading = findViewById(R.id.progressLoading);
        rvDownloads = findViewById(R.id.rvDownloads);
        tvEmpty = findViewById(R.id.tvEmpty);

        // Default test URL
        etUrl.setText("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        adapter = new DownloadListAdapter(this);
        adapter.setOnTaskRemovedListener(this::updateEmptyState);
        adapter.setOnTaskDeleteListener((taskId, deleteFile) -> {
            if (serviceBound) {
                downloadService.removeTask(taskId);
            }
        });
        rvDownloads.setLayoutManager(new LinearLayoutManager(this));
        rvDownloads.setAdapter(adapter);
    }

    private void setupListeners() {
        btnParse.setOnClickListener(v -> {
            String url = etUrl.getText() != null ? etUrl.getText().toString().trim() : "";
            if (url.isEmpty()) {
                Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show();
                return;
            }

            String videoId = youtubeService.extractVideoId(url);
            if (videoId == null) {
                Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show();
                appendLog("ERROR", "Invalid URL: " + url);
                return;
            }

            parseVideo(videoId);
        });

        btnUpdateYtDlp.setOnClickListener(v -> updateYtDlp());
    }

    private void updateYtDlp() {
        btnUpdateYtDlp.setEnabled(false);
        Toast.makeText(this, "Updating yt-dlp...", Toast.LENGTH_SHORT).show();
        appendLog("INFO", "Updating yt-dlp...");

        new Thread(() -> {
            try {
                UpdateStatus status = YoutubeDL.getInstance().updateYoutubeDL(this, UpdateChannel.STABLE.INSTANCE);
                mainHandler.post(() -> {
                    btnUpdateYtDlp.setEnabled(true);
                    if (status == UpdateStatus.DONE) {
                        Toast.makeText(this, "yt-dlp updated successfully", Toast.LENGTH_SHORT).show();
                        appendLog("INFO", "yt-dlp updated successfully");
                    } else {
                        Toast.makeText(this, "yt-dlp is already up to date", Toast.LENGTH_SHORT).show();
                        appendLog("INFO", "yt-dlp already up to date");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    btnUpdateYtDlp.setEnabled(true);
                    Toast.makeText(this, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    appendLog("ERROR", "yt-dlp update failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void appendLog(String level, String message) {
        switch (level) {
            case "ERROR":
                AppLogger.e(TAG, message);
                break;
            case "WARN":
                AppLogger.w(TAG, message);
                break;
            case "INFO":
                AppLogger.i(TAG, message);
                break;
            default:
                AppLogger.d(TAG, message);
                break;
        }
    }

    private void parseVideo(String videoId) {
        pendingVideoId = videoId;
        showLoading(true);
        cardVideoInfo.setVisibility(View.GONE);
        appendLog("INFO", "Parsing video: " + videoId);

        youtubeService.parseVideo(videoId, new YoutubeService.ParseCallback() {
            @Override
            public void onSuccess(VideoInfo videoInfo) {
                mainHandler.post(() -> {
                    showLoading(false);
                    appendLog("INFO", "Parse success: " + videoInfo.getTitle());
                    displayVideoInfo(videoInfo);
                });
            }

            @Override
            public void onBotDetected() {
                mainHandler.post(() -> {
                    showLoading(false);
                    appendLog("WARN", "Bot detection triggered - opening WebView login");
                    Toast.makeText(MainActivity.this, R.string.error_bot_detection, Toast.LENGTH_SHORT).show();
                    launchWebViewLogin();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    showLoading(false);
                    appendLog("ERROR", "Parse failed: " + error);
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void displayVideoInfo(VideoInfo videoInfo) {
        currentVideoInfo = videoInfo;
        cardVideoInfo.setVisibility(View.VISIBLE);

        tvTitle.setText(videoInfo.getTitle());
        tvDuration.setText(videoInfo.getFormattedDuration());

        if (videoInfo.getThumbnailUrl() != null) {
            Glide.with(this)
                    .load(videoInfo.getThumbnailUrl())
                    .centerCrop()
                    .into(ivThumbnail);
        }

        buildFormatList(videoInfo);
    }

    private void buildFormatList(VideoInfo videoInfo) {
        llFormats.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        // --- Video section: top 3 highest resolution ---
        List<VideoInfo.FormatOption> videoFormats = videoInfo.getVideoFormats();
        if (videoFormats != null && !videoFormats.isEmpty()) {
            addSectionHeader("\u25B6  Video");
            int start = Math.max(0, videoFormats.size() - 3);
            List<VideoInfo.FormatOption> top3 = videoFormats.subList(start, videoFormats.size());
            LinearLayout row = createButtonRow();
            for (int i = top3.size() - 1; i >= 0; i--) {
                VideoInfo.FormatOption format = top3.get(i);
                String label = format.getQuality();
                String size = format.getContentLength() > 0 ? formatSize(format.getContentLength()) : "";
                addFormatChip(inflater, row, label, size, true,
                        v -> startFormatDownload(videoInfo, format, true));
            }
            llFormats.addView(row);
        }

        // --- Audio section: only the highest bitrate ---
        List<VideoInfo.FormatOption> audioFormats = videoInfo.getAudioFormats();
        if (audioFormats != null && !audioFormats.isEmpty()) {
            addSectionHeader("\u266A  Audio");
            VideoInfo.FormatOption bestAudio = audioFormats.get(audioFormats.size() - 1);
            String label = bestAudio.getQuality();
            String size = bestAudio.getContentLength() > 0 ? formatSize(bestAudio.getContentLength()) : "";
            LinearLayout row = createButtonRow();
            addFormatChip(inflater, row, label, size, false,
                    v -> startFormatDownload(videoInfo, bestAudio, false));
            // 2 spacers to keep button width = 1/3 (same as video buttons)
            for (int s = 0; s < 2; s++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, 0, 1f));
            row.addView(spacer);
            }
            llFormats.addView(row);
        }

        // --- Cover: click thumbnail to download ---
        List<String> thumbUrls = videoInfo.getThumbnailUrls();
        if (thumbUrls != null && !thumbUrls.isEmpty()) {
            String coverUrl = thumbUrls.get(thumbUrls.size() - 1);
            ivThumbnail.setOnClickListener(v -> startThumbnailDownload(videoInfo, coverUrl));
        }
    }

    private LinearLayout createButtonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void addSectionHeader(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(ContextCompat.getColor(this, R.color.on_surface_secondary));
        header.setTextSize(11);
        header.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        header.setPadding(8, 18, 4, 6);
        header.setLetterSpacing(0.08f);
        llFormats.addView(header);
    }

    private void addFormatChip(LayoutInflater inflater, LinearLayout row,
                               String quality, String size, boolean isVideo,
                               View.OnClickListener downloadListener) {
        View block = inflater.inflate(R.layout.item_format_option, row, false);
        TextView tvQuality = block.findViewById(R.id.tvFormatQuality);
        TextView tvSize = block.findViewById(R.id.tvFormatSize);

        tvQuality.setText(quality);
        tvSize.setText(size);

        if (isVideo) {
            block.setBackgroundResource(R.drawable.chip_video_hero);
        } else {
            block.setBackgroundResource(R.drawable.chip_audio_hero);
        }
        tvQuality.setTextColor(0xFFFFFFFF);
        tvSize.setTextColor(0xFFFFFFFF);

        block.setOnClickListener(downloadListener);
        row.addView(block);
    }

    private void startFormatDownload(VideoInfo videoInfo, VideoInfo.FormatOption format, boolean isVideo) {
        if (!serviceBound) return;

        String formatSpec;
        DownloadTask.DownloadType type;
        if (isVideo) {
            type = DownloadTask.DownloadType.VIDEO;
            if (!format.hasAudio()) {
                // Pure video -> auto-merge with best audio
                formatSpec = format.getFormatId() + "+bestaudio";
            } else {
                formatSpec = format.getFormatId();
            }
        } else {
            type = DownloadTask.DownloadType.AUDIO;
            formatSpec = format.getFormatId();
        }

        appendLog("INFO", "Download started: " + videoInfo.getTitle() + " [" + type + " " + format.getQuality() + " f=" + formatSpec + "]");

        downloadService.createTask(
                videoInfo.getVideoId(),
                videoInfo.getTitle(),
                videoInfo.getThumbnailUrl(),
                type,
                formatSpec
        );

        Toast.makeText(this, "Download started: " + format.getQuality(), Toast.LENGTH_SHORT).show();
    }

    private void startThumbnailDownload(VideoInfo videoInfo, String coverUrl) {
        if (!serviceBound) return;

        appendLog("INFO", "Cover download started: " + videoInfo.getTitle());

        downloadService.createThumbnailTask(
                videoInfo.getVideoId(),
                videoInfo.getTitle(),
                videoInfo.getThumbnailUrl(),
                coverUrl
        );

        Toast.makeText(this, "Cover download started", Toast.LENGTH_SHORT).show();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void launchWebViewLogin() {
        Intent intent = new Intent(this, CookieWebViewActivity.class);
        webViewLauncher.launch(intent);
    }

    private void showLoading(boolean show) {
        progressLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        btnParse.setEnabled(!show);
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getItemCount() == 0;
        tvEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvDownloads.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, DownloadService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION_PERMISSION);
            }
        }
    }

    private void handleShareIntent() {
        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                etUrl.setText(sharedText);
                mainHandler.postDelayed(() -> btnParse.performClick(), 500);
            }
        }
    }

    @Override
    public void onTaskAdded(DownloadTask task) {
        mainHandler.post(() -> {
            adapter.addTask(task);
            updateEmptyState();
        });
    }

    @Override
    public void onTaskUpdated(DownloadTask task) {
        mainHandler.post(() -> adapter.updateTask(task));
    }

    @Override
    public void onTaskCompleted(DownloadTask task) {
        mainHandler.post(() -> {
            adapter.updateTask(task);
            appendLog("INFO", "Download completed: " + task.getTitle());
            Toast.makeText(this, "Download completed: " + task.getTitle(), Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onTaskFailed(DownloadTask task) {
        mainHandler.post(() -> {
            adapter.updateTask(task);
            appendLog("ERROR", "Download failed: " + task.getTitle() + "\n" + task.getErrorMessage());
            Toast.makeText(this, "Download failed: " + task.getErrorMessage(), Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onNewLog(String fullLog) {
        // Log is still collected by AppLogger, just not displayed in UI
    }

    @Override
    protected void onDestroy() {
        AppLogger.removeListener(this);
        if (serviceBound) {
            downloadService.removeListener(this);
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            // Notification permission handled
        }
    }
}
