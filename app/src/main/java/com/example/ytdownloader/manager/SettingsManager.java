package com.example.ytdownloader.manager;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {
    private static final String PREF_NAME = "yt_downloader_settings";

    private static final String KEY_VIDEO_QUALITY = "video_quality";
    private static final String KEY_AUDIO_QUALITY = "audio_quality";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_MAX_CONCURRENT = "max_concurrent";
    private static final String KEY_SPEED_LIMIT = "speed_limit";
    private static final String KEY_DOWNLOAD_SUBTITLES = "download_subtitles";
    private static final String KEY_PROXY = "proxy";

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // Video quality
    public String getVideoQuality() {
        return prefs.getString(KEY_VIDEO_QUALITY, "best");
    }

    public void setVideoQuality(String quality) {
        prefs.edit().putString(KEY_VIDEO_QUALITY, quality).apply();
    }

    // Audio quality
    public String getAudioQuality() {
        return prefs.getString(KEY_AUDIO_QUALITY, "best");
    }

    public void setAudioQuality(String quality) {
        prefs.edit().putString(KEY_AUDIO_QUALITY, quality).apply();
    }

    // Dark mode
    public boolean isDarkMode() {
        return prefs.getBoolean(KEY_DARK_MODE, false);
    }

    public void setDarkMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply();
    }

    // Max concurrent downloads
    public int getMaxConcurrent() {
        return prefs.getInt(KEY_MAX_CONCURRENT, 1);
    }

    public void setMaxConcurrent(int max) {
        prefs.edit().putInt(KEY_MAX_CONCURRENT, max).apply();
    }

    // Speed limit
    public String getSpeedLimit() {
        return prefs.getString(KEY_SPEED_LIMIT, "unlimited");
    }

    public void setSpeedLimit(String limit) {
        prefs.edit().putString(KEY_SPEED_LIMIT, limit).apply();
    }

    // Download subtitles
    public boolean isDownloadSubtitles() {
        return prefs.getBoolean(KEY_DOWNLOAD_SUBTITLES, false);
    }

    public void setDownloadSubtitles(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_SUBTITLES, enabled).apply();
    }

    // Proxy
    public String getProxy() {
        return prefs.getString(KEY_PROXY, "");
    }

    public void setProxy(String proxy) {
        prefs.edit().putString(KEY_PROXY, proxy).apply();
    }
}
