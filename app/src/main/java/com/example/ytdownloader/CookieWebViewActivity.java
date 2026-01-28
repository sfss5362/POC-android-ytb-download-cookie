package com.example.ytdownloader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.ytdownloader.manager.CookieStorage;

public class CookieWebViewActivity extends AppCompatActivity {
    public static final String EXTRA_COOKIES = "cookies";
    private static final String YOUTUBE_URL = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com";

    private WebView webView;
    private ProgressBar progressBar;
    private CookieStorage cookieStorage;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressWebView);
        cookieStorage = new CookieStorage(this);

        setupWebView();
        loadYouTubeLogin();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        // Enable cookies
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkLoginStatus(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Stay in WebView for Google/YouTube URLs
                if (url.contains("google.com") || url.contains("youtube.com")) {
                    return false;
                }
                return super.shouldOverrideUrlLoading(view, url);
            }
        });
    }

    private void loadYouTubeLogin() {
        // Clear old cookies first
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();

        webView.loadUrl(YOUTUBE_URL);
    }

    private void checkLoginStatus(String url) {
        // Check if we're on YouTube main page (login successful)
        if (url.contains("youtube.com") && !url.contains("accounts.google.com") && !url.contains("ServiceLogin")) {
            String cookies = CookieManager.getInstance().getCookie("https://www.youtube.com");

            if (cookies != null && cookieStorage.containsRequiredCookies(cookies)) {
                // Login successful
                cookieStorage.saveCookies(cookies);

                Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show();

                Intent result = new Intent();
                result.putExtra(EXTRA_COOKIES, cookies);
                setResult(Activity.RESULT_OK, result);
                finish();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            setResult(Activity.RESULT_CANCELED);
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
