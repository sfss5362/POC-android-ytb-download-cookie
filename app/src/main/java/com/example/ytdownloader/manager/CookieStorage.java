package com.example.ytdownloader.manager;

import android.content.Context;
import android.content.SharedPreferences;

public class CookieStorage {
    private static final String PREF_NAME = "youtube_cookies";
    private static final String KEY_COOKIES = "cookies";

    private final SharedPreferences prefs;

    public CookieStorage(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveCookies(String cookies) {
        prefs.edit().putString(KEY_COOKIES, cookies).apply();
    }

    public String getCookies() {
        return prefs.getString(KEY_COOKIES, null);
    }

    public boolean hasCookies() {
        String cookies = getCookies();
        return cookies != null && !cookies.isEmpty() && cookies.contains("SAPISID");
    }

    public void clearCookies() {
        prefs.edit().remove(KEY_COOKIES).apply();
    }

    public String convertToCookiesTxt(String webViewCookies) {
        if (webViewCookies == null || webViewCookies.isEmpty()) {
            return null;
        }

        StringBuilder cookiesTxt = new StringBuilder();
        cookiesTxt.append("# Netscape HTTP Cookie File\n");
        cookiesTxt.append("# https://www.youtube.com\n\n");

        String[] cookies = webViewCookies.split(";");
        for (String cookie : cookies) {
            cookie = cookie.trim();
            if (cookie.isEmpty()) continue;

            String[] parts = cookie.split("=", 2);
            if (parts.length == 2) {
                String name = parts[0].trim();
                String value = parts[1].trim();
                // Format: domain, includeSubdomains, path, secure, expiry, name, value
                cookiesTxt.append(".youtube.com\tTRUE\t/\tTRUE\t0\t")
                        .append(name).append("\t").append(value).append("\n");
            }
        }

        return cookiesTxt.toString();
    }

    public boolean containsRequiredCookies(String cookies) {
        if (cookies == null) return false;
        return cookies.contains("SAPISID") && cookies.contains("SID");
    }
}
