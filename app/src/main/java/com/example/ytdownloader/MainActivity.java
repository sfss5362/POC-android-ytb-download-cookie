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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
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
import com.example.ytdownloader.model.DownloadTask;
import com.example.ytdownloader.model.VideoInfo;
import com.example.ytdownloader.service.DownloadService;
import com.example.ytdownloader.service.YoutubeService;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DownloadService.DownloadListener {
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1001;

    private TextInputEditText etUrl;
    private MaterialButton btnParse;
    private CardView cardVideoInfo;
    private ImageView ivThumbnail;
    private TextView tvTitle;
    private TextView tvDuration;
    private RadioGroup rgOptions;
    private Spinner spinnerFormat;
    private MaterialButton btnDownload;
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
                    // Login successful, refresh downloader and retry
                    youtubeService.refreshDownloader();
                    if (downloadService != null) {
                        downloadService.refreshYoutubeService();
                    }
                    if (pendingVideoId != null) {
                        parseVideo(pendingVideoId);
                    }
                } else {
                    Toast.makeText(this, "Login cancelled", Toast.LENGTH_SHORT).show();
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
        rgOptions = findViewById(R.id.rgOptions);
        spinnerFormat = findViewById(R.id.spinnerFormat);
        btnDownload = findViewById(R.id.btnDownload);
        progressLoading = findViewById(R.id.progressLoading);
        rvDownloads = findViewById(R.id.rvDownloads);
        tvEmpty = findViewById(R.id.tvEmpty);

        adapter = new DownloadListAdapter(this);
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
                return;
            }

            parseVideo(videoId);
        });

        rgOptions.setOnCheckedChangeListener((group, checkedId) -> updateFormatSpinner());

        btnDownload.setOnClickListener(v -> startDownload());
    }

    private void parseVideo(String videoId) {
        pendingVideoId = videoId;
        showLoading(true);
        cardVideoInfo.setVisibility(View.GONE);

        youtubeService.parseVideo(videoId, new YoutubeService.ParseCallback() {
            @Override
            public void onSuccess(VideoInfo videoInfo) {
                mainHandler.post(() -> {
                    showLoading(false);
                    displayVideoInfo(videoInfo);
                });
            }

            @Override
            public void onBotDetected() {
                mainHandler.post(() -> {
                    showLoading(false);
                    Toast.makeText(MainActivity.this, R.string.error_bot_detection, Toast.LENGTH_SHORT).show();
                    launchWebViewLogin();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    showLoading(false);
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

        updateFormatSpinner();
    }

    private void updateFormatSpinner() {
        if (currentVideoInfo == null) return;

        List<String> options = new ArrayList<>();
        List<VideoInfo.FormatOption> formats;

        int checkedId = rgOptions.getCheckedRadioButtonId();
        if (checkedId == R.id.rbVideoOnly) {
            formats = currentVideoInfo.getVideoFormats();
            // Show only formats with audio
            for (VideoInfo.FormatOption format : formats) {
                if (format.hasAudio()) {
                    options.add(format.toString());
                }
            }
        } else if (checkedId == R.id.rbAudioOnly) {
            formats = currentVideoInfo.getAudioFormats();
            for (VideoInfo.FormatOption format : formats) {
                options.add(format.toString());
            }
        } else {
            // Best quality merge - just show info
            options.add("Best video + Best audio (merged)");
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFormat.setAdapter(spinnerAdapter);
    }

    private void startDownload() {
        if (currentVideoInfo == null || !serviceBound) return;

        int checkedId = rgOptions.getCheckedRadioButtonId();
        DownloadTask.DownloadType downloadType;
        String videoItag = null;
        String audioItag = null;

        if (checkedId == R.id.rbVideoOnly) {
            downloadType = DownloadTask.DownloadType.VIDEO_ONLY;
            // Get selected format with audio
            List<VideoInfo.FormatOption> formats = currentVideoInfo.getVideoFormats();
            int selectedIndex = spinnerFormat.getSelectedItemPosition();
            int withAudioIndex = 0;
            for (VideoInfo.FormatOption format : formats) {
                if (format.hasAudio()) {
                    if (withAudioIndex == selectedIndex) {
                        videoItag = format.getItag();
                        break;
                    }
                    withAudioIndex++;
                }
            }
        } else if (checkedId == R.id.rbAudioOnly) {
            downloadType = DownloadTask.DownloadType.AUDIO_ONLY;
            List<VideoInfo.FormatOption> formats = currentVideoInfo.getAudioFormats();
            int selectedIndex = spinnerFormat.getSelectedItemPosition();
            if (selectedIndex < formats.size()) {
                audioItag = formats.get(selectedIndex).getItag();
            }
        } else {
            downloadType = DownloadTask.DownloadType.BEST_QUALITY_MERGE;
            videoItag = youtubeService.getBestVideoItag(currentVideoInfo);
            audioItag = youtubeService.getBestAudioItag(currentVideoInfo);
        }

        if ((downloadType == DownloadTask.DownloadType.VIDEO_ONLY && videoItag == null) ||
                (downloadType == DownloadTask.DownloadType.AUDIO_ONLY && audioItag == null) ||
                (downloadType == DownloadTask.DownloadType.BEST_QUALITY_MERGE && (videoItag == null || audioItag == null))) {
            Toast.makeText(this, "No suitable format found", Toast.LENGTH_SHORT).show();
            return;
        }

        downloadService.createTask(
                currentVideoInfo.getVideoId(),
                currentVideoInfo.getTitle(),
                currentVideoInfo.getThumbnailUrl(),
                downloadType,
                videoItag,
                audioItag
        );

        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();

        // Reset UI
        cardVideoInfo.setVisibility(View.GONE);
        etUrl.setText("");
        currentVideoInfo = null;
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
            Toast.makeText(this, "Download completed: " + task.getTitle(), Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onTaskFailed(DownloadTask task) {
        mainHandler.post(() -> {
            adapter.updateTask(task);
            Toast.makeText(this, "Download failed: " + task.getErrorMessage(), Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onDestroy() {
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
