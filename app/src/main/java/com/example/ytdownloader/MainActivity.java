package com.example.ytdownloader;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
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
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class MainActivity extends AppCompatActivity implements DownloadService.DownloadListener, AppLogger.LogListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1001;

    private TextInputEditText etUrl;
    private MaterialButton btnParse;
    private CardView cardVideoInfo;
    private ImageView ivThumbnail;
    private TextView tvTitle;
    private TextView tvDuration;
    private LinearLayout llFormats;
    private ProgressBar progressLoading;
    private RecyclerView rvDownloads;
    private TextView tvEmpty;

    // Log UI
    private View logToggleBar;
    private TextView tvLogToggle;
    private CardView cardLog;
    private TextView tvLog;
    private MaterialButton btnCopyLog;
    private MaterialButton btnClearLog;
    private boolean logExpanded = true;

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
                    // Login successful, refresh downloader and retry
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

            // Load existing tasks
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
        cardVideoInfo = findViewById(R.id.cardVideoInfo);
        ivThumbnail = findViewById(R.id.ivThumbnail);
        tvTitle = findViewById(R.id.tvTitle);
        tvDuration = findViewById(R.id.tvDuration);
        llFormats = findViewById(R.id.llFormats);
        progressLoading = findViewById(R.id.progressLoading);
        rvDownloads = findViewById(R.id.rvDownloads);
        tvEmpty = findViewById(R.id.tvEmpty);

        // Log UI
        logToggleBar = findViewById(R.id.logToggleBar);
        tvLogToggle = findViewById(R.id.tvLogToggle);
        cardLog = findViewById(R.id.cardLog);
        tvLog = findViewById(R.id.tvLog);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnClearLog = findViewById(R.id.btnClearLog);

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

        tvLogToggle.setOnClickListener(v -> toggleLogPanel());

        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("YTDownloader Log", AppLogger.getLog());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });

        btnClearLog.setOnClickListener(v -> {
            AppLogger.clear();
            tvLog.setText("");
            logExpanded = false;
            cardLog.setVisibility(View.GONE);
            logToggleBar.setVisibility(View.GONE);
        });
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

    private void toggleLogPanel() {
        logExpanded = !logExpanded;
        cardLog.setVisibility(logExpanded ? View.VISIBLE : View.GONE);
        tvLogToggle.setText(logExpanded ? "Log \u25BC" : "Log \u25B2");
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

        // --- Video section ---
        List<VideoInfo.FormatOption> videoFormats = videoInfo.getVideoFormats();
        if (videoFormats != null && !videoFormats.isEmpty()) {
            addSectionHeader("Video");
            for (VideoInfo.FormatOption format : videoFormats) {
                String label = format.getQuality();
                if (format.hasAudio()) {
                    label += " (with audio)";
                }
                String size = format.getContentLength() > 0 ? formatSize(format.getContentLength()) : "";
                addFormatRow(inflater, label, size, v -> startFormatDownload(videoInfo, format, true));
            }
        }

        // --- Audio section ---
        List<VideoInfo.FormatOption> audioFormats = videoInfo.getAudioFormats();
        if (audioFormats != null && !audioFormats.isEmpty()) {
            addSectionHeader("Audio");
            for (VideoInfo.FormatOption format : audioFormats) {
                String label = format.getQuality();
                String size = format.getContentLength() > 0 ? formatSize(format.getContentLength()) : "";
                addFormatRow(inflater, label, size, v -> startFormatDownload(videoInfo, format, false));
            }
        }

        // --- Other section (cover image) ---
        List<String> thumbUrls = videoInfo.getThumbnailUrls();
        if (thumbUrls != null && !thumbUrls.isEmpty()) {
            addSectionHeader("Other");
            String coverUrl = thumbUrls.get(thumbUrls.size() - 1);
            addFormatRow(inflater, "Cover image", "", v -> startThumbnailDownload(videoInfo, coverUrl));
        }
    }

    private void addSectionHeader(String title) {
        TextView header = new TextView(this);
        header.setText("── " + title + " ──");
        header.setTextColor(ContextCompat.getColor(this, R.color.on_surface_secondary));
        header.setTextSize(12);
        header.setPadding(4, 12, 4, 4);
        llFormats.addView(header);
    }

    private void addFormatRow(LayoutInflater inflater, String quality, String size, View.OnClickListener downloadListener) {
        View row = inflater.inflate(R.layout.item_format_option, llFormats, false);
        TextView tvQuality = row.findViewById(R.id.tvFormatQuality);
        TextView tvSize = row.findViewById(R.id.tvFormatSize);
        View btnDownload = row.findViewById(R.id.btnFormatDownload);

        tvQuality.setText(quality);
        tvSize.setText(size);
        btnDownload.setOnClickListener(downloadListener);

        llFormats.addView(row);
    }

    private void startFormatDownload(VideoInfo videoInfo, VideoInfo.FormatOption format, boolean isVideo) {
        if (!serviceBound) return;

        DownloadTask.DownloadType type = isVideo ? DownloadTask.DownloadType.VIDEO_ONLY : DownloadTask.DownloadType.AUDIO_ONLY;
        String videoItag = isVideo ? format.getItag() : null;
        String audioItag = isVideo ? null : format.getItag();

        appendLog("INFO", "Download started: " + videoInfo.getTitle() + " [" + type + " " + format.getQuality() + "]");

        downloadService.createTask(
                videoInfo.getVideoId(),
                videoInfo.getTitle(),
                videoInfo.getThumbnailUrl(),
                type,
                videoItag,
                audioItag
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
                // Auto parse after short delay
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
        mainHandler.post(() -> {
            tvLog.setText(fullLog);
            logToggleBar.setVisibility(View.VISIBLE);
            if (logExpanded) {
                cardLog.setVisibility(View.VISIBLE);
                ScrollView scrollView = (ScrollView) tvLog.getParent();
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
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
