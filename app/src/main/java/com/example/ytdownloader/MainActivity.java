package com.example.ytdownloader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.ytdownloader.adapter.DownloadListAdapter;
import com.example.ytdownloader.manager.AppLogger;
import com.example.ytdownloader.manager.CookieStorage;
import com.example.ytdownloader.manager.SettingsManager;
import com.example.ytdownloader.model.DownloadTask;
import com.example.ytdownloader.model.VideoInfo;
import com.example.ytdownloader.service.DownloadService;
import com.example.ytdownloader.service.YoutubeService;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel;
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DownloadService.DownloadListener, AppLogger.LogListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1001;
    private static final String YOUTUBE_LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com";

    // Download tab views
    private EditText etUrl;
    private MaterialButton btnParse;
    private View cardVideoInfo;
    private ImageView ivThumbnail;
    private TextView tvTitle;
    private TextView tvDuration;
    private LinearLayout llFormats;
    private LinearProgressIndicator progressLoading;
    private RecyclerView rvDownloads;
    private TextView tvEmpty;

    // Navigation views
    private BottomNavigationView bottomNav;
    private View downloadContent;
    private View loginContent;
    private View settingsContent;

    // Login tab views
    private EditText etWebViewUrl;
    private ProgressBar progressWebView;
    private WebView webViewLogin;
    private ImageButton btnWebViewHome;
    private TextView tvLoginStatus;
    private CookieStorage cookieStorage;

    // Settings views
    private TextView tvDownloadPath;
    private Spinner spinnerVideoQuality;
    private Spinner spinnerAudioQuality;
    private SwitchCompat switchDarkMode;
    private Spinner spinnerMaxConcurrent;
    private Spinner spinnerSpeedLimit;
    private SwitchCompat switchSubtitles;
    private EditText etProxy;
    private TextView tvCookieStatus;
    private TextView tvCookiePath;
    private MaterialButton btnGoToLogin;
    private MaterialButton btnUpdateYtDlp;
    private TextView tvYtDlpVersion;

    private SettingsManager settingsManager;
    private YoutubeService youtubeService;
    private DownloadService downloadService;
    private boolean serviceBound = false;
    private DownloadListAdapter adapter;
    private Handler mainHandler;

    private VideoInfo currentVideoInfo;
    private String pendingVideoId;
    private String lastAutoParseVideoId;
    private Runnable autoParseRunnable;
    private boolean isParsing = false;
    private boolean settingsInitialized = false;

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
        cookieStorage = new CookieStorage(this);
        settingsManager = new SettingsManager(this);

        AppLogger.addListener(this);

        initViews();
        setupListeners();
        setupBottomNav();
        setupWebView();
        initSettings();
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

        // Navigation views
        bottomNav = findViewById(R.id.bottomNav);
        downloadContent = findViewById(R.id.downloadContent);
        loginContent = findViewById(R.id.loginContent);
        settingsContent = findViewById(R.id.settingsContent);

        // Login tab views
        etWebViewUrl = findViewById(R.id.etWebViewUrl);
        progressWebView = findViewById(R.id.progressWebView);
        webViewLogin = findViewById(R.id.webViewLogin);
        btnWebViewHome = findViewById(R.id.btnWebViewHome);
        tvLoginStatus = findViewById(R.id.tvLoginStatus);

        // Settings views
        tvDownloadPath = findViewById(R.id.tvDownloadPath);
        spinnerVideoQuality = findViewById(R.id.spinnerVideoQuality);
        spinnerAudioQuality = findViewById(R.id.spinnerAudioQuality);
        switchDarkMode = findViewById(R.id.switchDarkMode);
        spinnerMaxConcurrent = findViewById(R.id.spinnerMaxConcurrent);
        spinnerSpeedLimit = findViewById(R.id.spinnerSpeedLimit);
        switchSubtitles = findViewById(R.id.switchSubtitles);
        etProxy = findViewById(R.id.etProxy);
        tvCookieStatus = findViewById(R.id.tvCookieStatus);
        tvCookiePath = findViewById(R.id.tvCookiePath);
        btnGoToLogin = findViewById(R.id.btnGoToLogin);
        btnUpdateYtDlp = findViewById(R.id.btnUpdateYtDlp);
        tvYtDlpVersion = findViewById(R.id.tvYtDlpVersion);

        // Default test URL
        etUrl.setText("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        adapter = new DownloadListAdapter(this);
        adapter.setOnTaskRemovedListener(this::updateEmptyState);
        adapter.setOnTaskDeleteListener((taskId, deleteFile) -> {
            if (serviceBound) {
                downloadService.removeTask(taskId);
            }
        });
        adapter.setOnTaskActionListener(new DownloadListAdapter.OnTaskActionListener() {
            @Override
            public void onPause(String taskId) {
                if (serviceBound) downloadService.pauseTask(taskId);
            }
            @Override
            public void onResume(String taskId) {
                if (serviceBound) downloadService.resumeTask(taskId);
            }
            @Override
            public void onCancel(String taskId) {
                if (serviceBound) downloadService.cancelTask(taskId);
            }
        });
        rvDownloads.setLayoutManager(new LinearLayoutManager(this));
        rvDownloads.setAdapter(adapter);
    }

    private void setupBottomNav() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_download) {
                switchTab(0);
                return true;
            } else if (id == R.id.nav_login) {
                switchTab(1);
                return true;
            } else if (id == R.id.nav_settings) {
                switchTab(2);
                return true;
            }
            return false;
        });
    }

    private void switchTab(int position) {
        downloadContent.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        loginContent.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        settingsContent.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        if (position == 2) {
            loadYtDlpVersion();
        }
    }

    private void loadYtDlpVersion() {
        new Thread(() -> {
            try {
                String version = YoutubeDL.getInstance().version(this);
                mainHandler.post(() -> tvYtDlpVersion.setText(version));
            } catch (Exception e) {
                mainHandler.post(() -> tvYtDlpVersion.setText("Unknown"));
            }
        }).start();
    }

    private void initSettings() {
        // Download path (read-only)
        File moviesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "YTDownloader");
        tvDownloadPath.setText(moviesDir.getAbsolutePath());

        // Load saved values into spinners
        String[] videoQualityValues = getResources().getStringArray(R.array.video_quality_values);
        String savedVideoQuality = settingsManager.getVideoQuality();
        for (int i = 0; i < videoQualityValues.length; i++) {
            if (videoQualityValues[i].equals(savedVideoQuality)) {
                spinnerVideoQuality.setSelection(i);
                break;
            }
        }

        String[] audioQualityValues = getResources().getStringArray(R.array.audio_quality_values);
        String savedAudioQuality = settingsManager.getAudioQuality();
        for (int i = 0; i < audioQualityValues.length; i++) {
            if (audioQualityValues[i].equals(savedAudioQuality)) {
                spinnerAudioQuality.setSelection(i);
                break;
            }
        }

        String[] maxConcurrentOptions = getResources().getStringArray(R.array.max_concurrent_options);
        int savedMaxConcurrent = settingsManager.getMaxConcurrent();
        for (int i = 0; i < maxConcurrentOptions.length; i++) {
            if (Integer.parseInt(maxConcurrentOptions[i]) == savedMaxConcurrent) {
                spinnerMaxConcurrent.setSelection(i);
                break;
            }
        }

        String[] speedLimitValues = getResources().getStringArray(R.array.speed_limit_values);
        String savedSpeedLimit = settingsManager.getSpeedLimit();
        for (int i = 0; i < speedLimitValues.length; i++) {
            if (speedLimitValues[i].equals(savedSpeedLimit)) {
                spinnerSpeedLimit.setSelection(i);
                break;
            }
        }

        switchSubtitles.setChecked(settingsManager.isDownloadSubtitles());
        etProxy.setText(settingsManager.getProxy());

        // Mark initialized after setting initial values, to prevent Spinner listeners firing on init
        mainHandler.post(() -> {
            settingsInitialized = true;
        });

        // Spinner listeners
        spinnerVideoQuality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!settingsInitialized) return;
                String[] values = getResources().getStringArray(R.array.video_quality_values);
                settingsManager.setVideoQuality(values[pos]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerAudioQuality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!settingsInitialized) return;
                String[] values = getResources().getStringArray(R.array.audio_quality_values);
                settingsManager.setAudioQuality(values[pos]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerMaxConcurrent.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!settingsInitialized) return;
                String[] options = getResources().getStringArray(R.array.max_concurrent_options);
                settingsManager.setMaxConcurrent(Integer.parseInt(options[pos]));
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerSpeedLimit.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!settingsInitialized) return;
                String[] values = getResources().getStringArray(R.array.speed_limit_values);
                settingsManager.setSpeedLimit(values[pos]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        switchSubtitles.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!settingsInitialized) return;
            settingsManager.setDownloadSubtitles(isChecked);
        });

        etProxy.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                settingsManager.setProxy(etProxy.getText().toString().trim());
            }
        });

        // Cookie status + go to login
        btnGoToLogin.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.nav_login));
        updateCookieStatus();

        // yt-dlp update button
        btnUpdateYtDlp.setOnClickListener(v -> updateYtDlp());

        // yt-dlp version (load in background)
        loadYtDlpVersion();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings settings = webViewLogin.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webViewLogin, true);

        // JS interface for download buttons
        webViewLogin.addJavascriptInterface(new WebViewDownloadInterface(), "YTDownloader");

        webViewLogin.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressWebView.setVisibility(View.VISIBLE);
                } else {
                    progressWebView.setVisibility(View.GONE);
                }
            }
        });

        webViewLogin.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                etWebViewUrl.setText(url);
                checkLoginStatus(url);
                injectDownloadButtons(view);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // SPA navigation (YouTube uses History API): update address bar + re-inject buttons
                etWebViewUrl.setText(url);
                injectDownloadButtons(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.contains("google.com") || url.contains("youtube.com")) {
                    return false;
                }
                return super.shouldOverrideUrlLoading(view, url);
            }
        });

        // Home button -> navigate to m.youtube.com
        btnWebViewHome.setOnClickListener(v -> {
            webViewLogin.loadUrl("https://m.youtube.com");
            etWebViewUrl.setText("https://m.youtube.com");
        });

        // Load YouTube homepage by default
        webViewLogin.loadUrl("https://m.youtube.com");
        etWebViewUrl.setText("https://m.youtube.com");

        // Check initial login status
        updateLoginStatusBanner();

        // Address bar: navigate on Enter/Go, only allow Google-family domains
        etWebViewUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String url = etWebViewUrl.getText().toString().trim();
                if (!url.isEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    if (!isAllowedDomain(url)) {
                        Toast.makeText(this, "Only Google/YouTube sites are allowed", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    webViewLogin.loadUrl(url);
                }
                return true;
            }
            return false;
        });
    }

    private void injectDownloadButtons(WebView view) {
        // All DOM manipulation uses createElement/createElementNS to comply with YouTube's Trusted Types CSP.
        // Never use innerHTML/outerHTML/insertAdjacentHTML.
        String js = "(function() {" +
            "try {" +
            // Cleanup previous injection
            "if (window._ytdlObserver) { window._ytdlObserver.disconnect(); window._ytdlObserver = null; }" +
            "if (window._ytdlInterval) { clearInterval(window._ytdlInterval); window._ytdlInterval = null; }" +
            "document.querySelectorAll('.ytdl-btn').forEach(function(b){b.remove();});" +

            // Inject CSS via textContent (safe, not innerHTML)
            "var style = document.getElementById('ytdl-style');" +
            "if (!style) {" +
                "style = document.createElement('style');" +
                "style.id = 'ytdl-style';" +
                "style.textContent = " +
                    // Thumbnail overlay button (for list views)
                    "'.ytdl-btn {" +
                        "position:absolute !important; bottom:12px !important; left:50% !important; right:auto !important;" +
                        "z-index:2147483647 !important;" +
                        "width:36px !important; height:36px !important; border-radius:50% !important;" +
                        "background:linear-gradient(135deg,rgba(245,124,56,0.95),rgba(220,80,30,0.95)) !important;" +
                        "border:2px solid rgba(255,255,255,0.85) !important;" +
                        "display:flex !important; align-items:center !important; justify-content:center !important;" +
                        "cursor:pointer !important;" +
                        "box-shadow:0 2px 8px rgba(0,0,0,0.3),0 0 0 0 rgba(232,101,43,0.4) !important;" +
                        "padding:0 !important; margin:0 !important; pointer-events:auto !important;" +
                        "opacity:1 !important; visibility:visible !important;" +
                        "-webkit-tap-highlight-color:transparent !important;" +
                        "animation:ytdl-enter 0.4s cubic-bezier(0.175,0.885,0.32,1.275) both, ytdl-ring 3s ease-out 1s infinite !important;" +
                    "}" +
                    // Fixed floating button (for watch/shorts pages)
                    ".ytdl-btn-fixed {" +
                        "position:fixed !important;" +
                        "z-index:2147483647 !important;" +
                        "width:44px !important; height:44px !important; border-radius:50% !important;" +
                        "background:linear-gradient(135deg,rgba(245,124,56,0.97),rgba(220,80,30,0.97)) !important;" +
                        "border:2px solid rgba(255,255,255,0.9) !important;" +
                        "display:flex !important; align-items:center !important; justify-content:center !important;" +
                        "cursor:pointer !important;" +
                        "box-shadow:0 3px 12px rgba(0,0,0,0.4),0 0 0 0 rgba(232,101,43,0.4) !important;" +
                        "padding:0 !important; margin:0 !important; pointer-events:auto !important;" +
                        "opacity:1 !important; visibility:visible !important;" +
                        "-webkit-tap-highlight-color:transparent !important;" +
                        "animation:ytdl-enter-abs 0.4s cubic-bezier(0.175,0.885,0.32,1.275) both, ytdl-ring 3s ease-out 1s infinite !important;" +
                    "}" +

                    // Hover / Active
                    ".ytdl-btn:hover { cursor:grab !important; transform:translateX(-50%) scale(1.15) !important; box-shadow:0 4px 20px rgba(232,101,43,0.6) !important; }" +
                    ".ytdl-btn:active { cursor:grabbing !important; transform:translateX(-50%) scale(0.88) !important; background:linear-gradient(135deg,rgba(196,71,26,0.95),rgba(170,55,15,0.95)) !important; }" +
                    ".ytdl-btn-fixed:hover { cursor:grab !important; transform:scale(1.15) !important; box-shadow:0 4px 20px rgba(232,101,43,0.6) !important; }" +
                    ".ytdl-btn-fixed:active { cursor:grabbing !important; transform:scale(0.88) !important; background:linear-gradient(135deg,rgba(196,71,26,0.95),rgba(170,55,15,0.95)) !important; }" +
                    // Player button: right-aligned in player container
                    ".ytdl-btn-player { left:auto !important; right:10px !important; bottom:10px !important; transform:none !important; animation:ytdl-enter-abs 0.4s cubic-bezier(0.175,0.885,0.32,1.275) both, ytdl-ring 3s ease-out 1s infinite !important; }" +
                    ".ytdl-btn-player:hover { cursor:grab !important; transform:scale(1.15) !important; box-shadow:0 4px 20px rgba(232,101,43,0.6) !important; }" +
                    ".ytdl-btn-player:active { cursor:grabbing !important; transform:scale(0.88) !important; background:linear-gradient(135deg,rgba(196,71,26,0.95),rgba(170,55,15,0.95)) !important; }" +

                    // Entry: scale from 0 with bounce (centered buttons with translateX)
                    "@keyframes ytdl-enter {" +
                        "0% { opacity:0; transform:translateX(-50%) scale(0); }" +
                        "100% { opacity:1; transform:translateX(-50%) scale(1); }" +
                    "}" +
                    // Entry without translateX (for fixed/player buttons)
                    "@keyframes ytdl-enter-abs {" +
                        "0% { opacity:0; transform:scale(0); }" +
                        "100% { opacity:1; transform:scale(1); }" +
                    "}" +
                    // Expanding ring pulse on button
                    "@keyframes ytdl-ring {" +
                        "0% { box-shadow:0 2px 8px rgba(0,0,0,0.3),0 0 0 0 rgba(232,101,43,0.5); }" +
                        "40% { box-shadow:0 2px 8px rgba(0,0,0,0.3),0 0 0 10px rgba(232,101,43,0); }" +
                        "100% { box-shadow:0 2px 8px rgba(0,0,0,0.3),0 0 0 0 rgba(232,101,43,0); }" +
                    "}" +

                    // SVG arrow: bouncy download motion + scale pulse
                    ".ytdl-btn svg,.ytdl-btn-fixed svg {" +
                        "width:20px !important; height:20px !important; pointer-events:none !important;" +
                        "animation:ytdl-arrow 2s cubic-bezier(0.4,0,0.2,1) infinite !important;" +
                        "filter:drop-shadow(0 1px 2px rgba(0,0,0,0.3)) !important;" +
                    "}" +
                    ".ytdl-btn-fixed svg { width:24px !important; height:24px !important; }" +
                    "@keyframes ytdl-arrow {" +
                        "0%,100% { transform:translateY(0) scale(1); opacity:1; }" +
                        "12% { transform:translateY(5px) scale(0.85); opacity:0.65; }" +
                        "28% { transform:translateY(-3px) scale(1.15); opacity:1; }" +
                        "42% { transform:translateY(2px) scale(0.93); opacity:0.8; }" +
                        "56% { transform:translateY(-1px) scale(1.05); opacity:1; }" +
                        "70%,100% { transform:translateY(0) scale(1); opacity:1; }" +
                    "}';" +
                "document.head.appendChild(style);" +
            "}" +

            // Create SVG icon using DOM API (Trusted Types safe)
            "function makeSvg() {" +
                "var ns = 'http://www.w3.org/2000/svg';" +
                "var svg = document.createElementNS(ns, 'svg');" +
                "svg.setAttribute('viewBox', '0 0 24 24');" +
                "svg.setAttribute('fill', 'white');" +
                "var p = document.createElementNS(ns, 'path');" +
                "p.setAttribute('d', 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z');" +
                "svg.appendChild(p);" +
                "return svg;" +
            "}" +

            // Add button to a container. posTop=true puts it at top-right (for shorts)
            "function addBtn(container, videoUrl, posTop) {" +
                "if (!container || container.querySelector('.ytdl-btn')) return;" +
                "var cs = getComputedStyle(container);" +
                "if (cs.position === 'static' || cs.position === '') container.style.position = 'relative';" +
                // Ensure no ancestor clips the button
                "var anc = container;" +
                "for (var d=0; d<5 && anc; d++) {" +
                    "anc.style.overflow = 'visible';" +
                    "anc = anc.parentElement;" +
                "}" +
                "var btn = document.createElement('div');" +
                "btn.className = 'ytdl-btn';" +
                "if (posTop) {" +
                    "btn.style.bottom = 'auto';" +
                    "btn.style.top = '55%';" +
                "}" +
                "btn.appendChild(makeSvg());" +
                "btn.addEventListener('click', function(e) {" +
                    "e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();" +
                    "YTDownloader.downloadVideo(videoUrl);" +
                "}, true);" +
                "btn.addEventListener('touchend', function(e) {" +
                    "e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();" +
                    "YTDownloader.downloadVideo(videoUrl);" +
                "}, true);" +
                "container.appendChild(btn);" +
            "}" +

            // Add a fixed-position button to document body (for watch/shorts pages)
            "function addFixedBtn(videoUrl, bottom, right, left) {" +
                "if (document.querySelector('.ytdl-btn-fixed')) return;" +
                "var btn = document.createElement('div');" +
                "btn.className = 'ytdl-btn-fixed';" +
                "btn.style.bottom = bottom;" +
                "if (right) btn.style.right = right;" +
                "if (left) btn.style.left = left;" +
                "btn.appendChild(makeSvg());" +
                "btn.addEventListener('click', function(e) {" +
                    "e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();" +
                    "YTDownloader.downloadVideo(videoUrl);" +
                "}, true);" +
                "btn.addEventListener('touchend', function(e) {" +
                    "e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();" +
                    "YTDownloader.downloadVideo(videoUrl);" +
                "}, true);" +
                "document.body.appendChild(btn);" +
            "}" +

            // Extract video ID -> full URL
            "function extractUrl(el) {" +
                "var anchors = [];" +
                "if (el.tagName === 'A') anchors.push(el);" +
                "var found = el.querySelectorAll('a[href]');" +
                "for (var k=0; k<found.length; k++) anchors.push(found[k]);" +
                "var p = el.closest('a[href]'); if (p) anchors.push(p);" +
                "for (var i=0; i<anchors.length; i++) {" +
                    "var h = anchors[i].href || '';" +
                    "var m = h.match(/(?:watch\\?v=|watch%3Fv%3D|shorts\\/|v\\/)([a-zA-Z0-9_-]{11})/);" +
                    "if (m) return 'https://www.youtube.com/watch?v=' + m[1];" +
                "}" +
                "return null;" +
            "}" +

            // Find best thumbnail container
            "function findThumb(el) {" +
                "var selectors = [" +
                    "'ytm-thumbnail-cover', '.media-item-thumbnail-container'," +
                    "'.video-thumbnail-container-vertical', '.video-thumbnail-container-large'," +
                    "'.video-thumbnail-container', '.thumbnail-container'," +
                    "'thumbnail', 'ytm-thumbnail-overlay-time-status-renderer'" +
                "];" +
                "for (var i=0; i<selectors.length; i++) {" +
                    "var t = el.querySelector(selectors[i]);" +
                    "if (t) return t;" +
                "}" +
                "var imgs = el.querySelectorAll('img');" +
                "for (var j=0; j<imgs.length; j++) {" +
                    "var src = imgs[j].src || imgs[j].getAttribute('data-thumb') || '';" +
                    "if (src.indexOf('ytimg') !== -1 || src.indexOf('ggpht') !== -1 || src.indexOf('googleusercontent') !== -1) {" +
                        "return imgs[j].parentElement;" +
                    "}" +
                "}" +
                "if (imgs.length > 0) return imgs[0].parentElement;" +
                "return null;" +
            "}" +

            // Main scan
            "function scan() {" +
                "var count = 0;" +
                // Shorts element tag names (button goes to top-right)
                "var shortsTags = ['ytm-shorts-lockup-view-model-v2','ytm-shorts-lockup-view-model','ytm-reel-item-renderer'];" +
                "function isShorts(el) {" +
                    "var tag = el.tagName ? el.tagName.toLowerCase() : '';" +
                    "for (var s=0; s<shortsTags.length; s++) { if (tag === shortsTags[s]) return true; }" +
                    // Also check if the link is a /shorts/ URL
                    "var a = el.querySelector('a[href*=\"/shorts/\"]');" +
                    "if (a) return true;" +
                    "return false;" +
                "}" +

                // Strategy 1: YouTube mobile custom elements
                "var sel = 'ytm-rich-item-renderer,ytm-video-with-context-renderer," +
                    "ytm-compact-video-renderer,ytm-media-item," +
                    "ytm-shorts-lockup-view-model-v2,ytm-shorts-lockup-view-model," +
                    "ytm-reel-item-renderer';" +
                "document.querySelectorAll(sel).forEach(function(el) {" +
                    "var url = extractUrl(el);" +
                    "if (!url) return;" +
                    "var thumb = findThumb(el);" +
                    "if (thumb) { addBtn(thumb, url, isShorts(el)); count++; }" +
                "});" +
                // Strategy 2: <a> with watch/shorts containing <img>
                "document.querySelectorAll('a[href*=\"/watch?v=\"], a[href*=\"/shorts/\"]').forEach(function(a) {" +
                    "var img = a.querySelector('img');" +
                    "if (!img) return;" +
                    "var url = extractUrl(a);" +
                    "if (!url) return;" +
                    "var isSh = a.href && a.href.indexOf('/shorts/') !== -1;" +
                    "addBtn(img.parentElement, url, isSh); count++;" +
                "});" +
                // Strategy 3: ytimg images near video links
                "document.querySelectorAll('img').forEach(function(img) {" +
                    "if (img.closest('.ytdl-btn')) return;" +
                    "var src = img.src || '';" +
                    "if (src.indexOf('ytimg.com') === -1 && src.indexOf('i.ytimg') === -1) return;" +
                    "if (img.parentElement && img.parentElement.querySelector('.ytdl-btn')) return;" +
                    "var container = img.parentElement;" +
                    "var url = extractUrl(container);" +
                    "if (!url && container.parentElement) url = extractUrl(container.parentElement);" +
                    "if (!url && container.parentElement && container.parentElement.parentElement) url = extractUrl(container.parentElement.parentElement);" +
                    "if (url) { addBtn(container, url, false); count++; }" +
                "});" +

                // Strategy 4: Watch page — button on the player container's bottom-right
                "var loc = window.location.href;" +
                "var watchMatch = loc.match(/(?:watch\\?v=|watch%3Fv%3D)([a-zA-Z0-9_-]{11})/);" +
                "if (watchMatch) {" +
                    "var wUrl = 'https://www.youtube.com/watch?v=' + watchMatch[1];" +
                    // Find the video player container
                    "var playerSels = [" +
                        "'.player-container', '.html5-video-player', 'ytm-player-microformat-renderer'," +
                        "'ytm-media-control-container', '.ytm-autonav-bar', '#player', '.player-controls-content'," +
                        "'video'" +
                    "];" +
                    "var playerEl = null;" +
                    "for (var pi=0; pi<playerSels.length; pi++) {" +
                        "playerEl = document.querySelector(playerSels[pi]);" +
                        "if (playerEl) break;" +
                    "}" +
                    // Use the video element's parent as container
                    "if (playerEl && playerEl.tagName === 'VIDEO') playerEl = playerEl.parentElement;" +
                    "if (playerEl && !playerEl.querySelector('.ytdl-btn-player')) {" +
                        "var pcs = getComputedStyle(playerEl);" +
                        "if (pcs.position === 'static' || pcs.position === '') playerEl.style.position = 'relative';" +
                        "var pbtn = document.createElement('div');" +
                        "pbtn.className = 'ytdl-btn ytdl-btn-player';" +
                        "pbtn.appendChild(makeSvg());" +
                        "pbtn.addEventListener('click', function(e) {" +
                            "e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();" +
                            "YTDownloader.downloadVideo(wUrl);" +
                        "}, true);" +
                        "pbtn.addEventListener('touchend', function(e) {" +
                            "e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();" +
                            "YTDownloader.downloadVideo(wUrl);" +
                        "}, true);" +
                        "playerEl.appendChild(pbtn);" +
                        "count++;" +
                    "}" +
                "}" +

                // Strategy 5: Shorts page — fixed button, left side to avoid YouTube controls on right
                "var shortsMatch = loc.match(/shorts\\/([a-zA-Z0-9_-]{11})/);" +
                "if (shortsMatch && !document.querySelector('.ytdl-btn-fixed')) {" +
                    "var sUrl = 'https://www.youtube.com/watch?v=' + shortsMatch[1];" +
                    "addFixedBtn(sUrl, '160px', null, '16px');" +
                    "count++;" +
                "}" +

                // Remove stale buttons when navigating away
                "if (!watchMatch) {" +
                    "var sp = document.querySelector('.ytdl-btn-player');" +
                    "if (sp) sp.remove();" +
                "}" +
                "if (!shortsMatch) {" +
                    "var sf = document.querySelector('.ytdl-btn-fixed');" +
                    "if (sf) sf.remove();" +
                "}" +
            "}" +

            // Initial scan after DOM settles
            "setTimeout(scan, 800);" +

            // MutationObserver for dynamic content
            "var _ytdlTimer = null;" +
            "window._ytdlObserver = new MutationObserver(function() {" +
                "clearTimeout(_ytdlTimer);" +
                "_ytdlTimer = setTimeout(scan, 600);" +
            "});" +
            "if (document.body) {" +
                "window._ytdlObserver.observe(document.body, {childList:true, subtree:true});" +
            "}" +

            // Periodic fallback for lazy-loaded content
            "window._ytdlInterval = setInterval(scan, 3000);" +

            "} catch(e) { console.log('[YTDownloader] inject error: ' + e.message); }" +
        "})();";

        view.evaluateJavascript(js, null);
    }

    /** JS interface: receives video URL from injected download buttons */
    private class WebViewDownloadInterface {
        @JavascriptInterface
        public void downloadVideo(String videoUrl) {
            mainHandler.post(() -> {
                // Switch to Download tab
                bottomNav.setSelectedItemId(R.id.nav_download);
                // Set lastAutoParseVideoId first so TextWatcher won't double-fire
                String videoId = youtubeService.extractVideoId(videoUrl);
                if (videoId != null) {
                    lastAutoParseVideoId = videoId;
                }
                etUrl.setText(videoUrl);
                if (videoId != null) {
                    parseVideo(videoId);
                }
            });
        }
    }

    private boolean isAllowedDomain(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("google.com") || lower.contains("google.co.")
                || lower.contains("youtube.com") || lower.contains("youtu.be")
                || lower.contains("gstatic.com") || lower.contains("googleapis.com");
    }

    private boolean loginHandled = false;

    private void updateLoginStatusBanner() {
        if (cookieStorage.hasCookies()) {
            tvLoginStatus.setText(R.string.login_status_logged_in);
            tvLoginStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
            tvLoginStatus.setBackgroundColor(0x0A00C853);
        } else {
            tvLoginStatus.setText(R.string.login_status_not_logged_in);
            tvLoginStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_secondary));
            tvLoginStatus.setBackgroundColor(0x0A000000);
        }
    }

    private void updateCookieStatus() {
        if (cookieStorage.hasCookies()) {
            tvCookieStatus.setText(R.string.settings_cookie_logged_in);
            tvCookieStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
            File cookieFile = new File(getCacheDir(), "cookies.txt");
            tvCookiePath.setText(cookieFile.getAbsolutePath());
            tvCookiePath.setVisibility(View.VISIBLE);
            btnGoToLogin.setVisibility(View.GONE);
        } else {
            tvCookieStatus.setText(R.string.settings_cookie_not_logged_in);
            tvCookieStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_secondary));
            tvCookiePath.setVisibility(View.GONE);
            btnGoToLogin.setVisibility(View.VISIBLE);
        }
    }

    private void checkLoginStatus(String url) {
        if (loginHandled) return;
        if (url.contains("youtube.com") && !url.contains("accounts.google.com") && !url.contains("ServiceLogin")) {
            String cookies = CookieManager.getInstance().getCookie("https://www.youtube.com");

            if (cookies != null && cookieStorage.containsRequiredCookies(cookies)) {
                loginHandled = true;
                cookieStorage.saveCookies(cookies);
                Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show();
                appendLog("INFO", "Login successful, cookies saved.");

                // Update login status banner + settings cookie status
                updateLoginStatusBanner();
                updateCookieStatus();

                // Refresh services
                youtubeService.refreshDownloader();
                if (downloadService != null) {
                    downloadService.refreshYoutubeService();
                }

                // Navigate to YouTube homepage
                webViewLogin.loadUrl("https://m.youtube.com");

                // Retry parse in background if there was a pending video
                if (pendingVideoId != null) {
                    parseVideo(pendingVideoId);
                }
            }
        }
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

            lastAutoParseVideoId = videoId;
            parseVideo(videoId);
        });

        // Auto-parse: detect valid YouTube URL on text change
        etUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (autoParseRunnable != null) {
                    mainHandler.removeCallbacks(autoParseRunnable);
                }
                autoParseRunnable = () -> {
                    String text = s.toString().trim();
                    if (text.isEmpty()) return;
                    String videoId = youtubeService.extractVideoId(text);
                    if (videoId != null && !videoId.equals(lastAutoParseVideoId)) {
                        lastAutoParseVideoId = videoId;
                        parseVideo(videoId);
                    }
                };
                mainHandler.postDelayed(autoParseRunnable, 300);
            }
        });
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
                        loadYtDlpVersion();
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
        if (isParsing) return;
        isParsing = true;
        pendingVideoId = videoId;
        showLoading(true);
        cardVideoInfo.setVisibility(View.GONE);
        appendLog("INFO", "Parsing video: " + videoId);

        youtubeService.parseVideo(videoId, new YoutubeService.ParseCallback() {
            @Override
            public void onSuccess(VideoInfo videoInfo) {
                mainHandler.post(() -> {
                    isParsing = false;
                    showLoading(false);
                    appendLog("INFO", "Parse success: " + videoInfo.getTitle());
                    displayVideoInfo(videoInfo);
                });
            }

            @Override
            public void onBotDetected() {
                mainHandler.post(() -> {
                    isParsing = false;
                    showLoading(false);
                    appendLog("WARN", "Bot detection triggered - opening WebView login");
                    Toast.makeText(MainActivity.this, R.string.error_bot_detection, Toast.LENGTH_SHORT).show();
                    launchWebViewLogin();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    isParsing = false;
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

        boolean hasActive = serviceBound && downloadService.hasActiveDownload(videoInfo.getVideoId());

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
                addFormatChip(inflater, row, label, size, true, hasActive,
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
            addFormatChip(inflater, row, label, size, false, hasActive,
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
                               boolean disabled, View.OnClickListener downloadListener) {
        View block = inflater.inflate(R.layout.item_format_option, row, false);
        TextView tvQuality = block.findViewById(R.id.tvFormatQuality);
        TextView tvSize = block.findViewById(R.id.tvFormatSize);

        tvQuality.setText(quality);
        tvSize.setText(size);

        if (disabled) {
            // Disabled state
            if (isVideo) {
                block.setBackgroundResource(R.drawable.chip_video_disabled);
            } else {
                block.setBackgroundResource(R.drawable.chip_audio_disabled);
            }
            tvQuality.setTextColor(0x80FFFFFF);
            tvSize.setTextColor(0x80FFFFFF);
            block.setAlpha(0.5f);
            block.setClickable(false);
        } else {
            // Normal state
            if (isVideo) {
                block.setBackgroundResource(R.drawable.chip_video_hero);
            } else {
                block.setBackgroundResource(R.drawable.chip_audio_hero);
            }
            tvQuality.setTextColor(0xFFFFFFFF);
            tvSize.setTextColor(0xFFFFFFFF);
            block.setOnClickListener(downloadListener);
        }

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

        // Refresh format chips to disable buttons
        buildFormatList(videoInfo);
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
        // Switch to Login tab via bottom nav
        bottomNav.setSelectedItemId(R.id.nav_login);

        // Reset login detection flag
        loginHandled = false;

        // Clear old cookies and load login URL
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();

        webViewLogin.loadUrl(YOUTUBE_LOGIN_URL);
        etWebViewUrl.setText(YOUTUBE_LOGIN_URL);
    }

    private void showLoading(boolean show) {
        progressLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        btnParse.setEnabled(!show);
        btnParse.setText(show ? R.string.status_parsing : R.string.btn_parse);
        btnParse.setAlpha(show ? 0.5f : 1.0f);
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

    private void refreshFormatChipsIfNeeded(String videoId) {
        if (currentVideoInfo != null && currentVideoInfo.getVideoId().equals(videoId)) {
            buildFormatList(currentVideoInfo);
        }
    }

    @Override
    public void onBackPressed() {
        int selectedId = bottomNav.getSelectedItemId();
        if (selectedId == R.id.nav_login) {
            if (webViewLogin.canGoBack()) {
                webViewLogin.goBack();
                return;
            }
            // Switch back to Download tab
            bottomNav.setSelectedItemId(R.id.nav_download);
            return;
        } else if (selectedId == R.id.nav_settings) {
            // Switch back to Download tab
            bottomNav.setSelectedItemId(R.id.nav_download);
            return;
        }
        super.onBackPressed();
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
            refreshFormatChipsIfNeeded(task.getVideoId());
        });
    }

    @Override
    public void onTaskFailed(DownloadTask task) {
        mainHandler.post(() -> {
            adapter.updateTask(task);
            appendLog("ERROR", "Download failed: " + task.getTitle() + "\n" + task.getErrorMessage());
            Toast.makeText(this, "Download failed: " + task.getErrorMessage(), Toast.LENGTH_LONG).show();
            refreshFormatChipsIfNeeded(task.getVideoId());
        });
    }

    @Override
    public void onNewLog(String fullLog) {
        // Log is still collected by AppLogger, just not displayed in UI
    }

    @Override
    protected void onDestroy() {
        AppLogger.removeListener(this);
        if (webViewLogin != null) {
            webViewLogin.destroy();
        }
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
