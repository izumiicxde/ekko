package com.semantic.ekko.util;

import android.content.Context;
import android.content.SharedPreferences;

public class PrefsManager {

    private static final String PREFS_NAME = "ekko_prefs";

    // Keys
    private static final String KEY_ONBOARDING_DONE   = "onboarding_done";
    private static final String KEY_PIN_ENABLED        = "pin_enabled";
    private static final String KEY_PIN_HASH           = "pin_hash";
    private static final String KEY_THEME              = "theme"; // system, light, dark
    private static final String KEY_LAST_INDEXED_AT    = "last_indexed_at";
    private static final String KEY_SORT_ORDER         = "sort_order";
    private static final String KEY_FILTER_FILE_TYPE   = "filter_file_type";

    private final SharedPreferences prefs;

    // =========================
    // INIT
    // =========================

    public PrefsManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // =========================
    // ONBOARDING
    // =========================

    public boolean isOnboardingDone() {
        return prefs.getBoolean(KEY_ONBOARDING_DONE, false);
    }

    public void setOnboardingDone(boolean done) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply();
    }

    // =========================
    // PIN AUTH
    // =========================

    public boolean isPinEnabled() {
        return prefs.getBoolean(KEY_PIN_ENABLED, false);
    }

    public void setPinEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PIN_ENABLED, enabled).apply();
    }

    public String getPinHash() {
        return prefs.getString(KEY_PIN_HASH, null);
    }

    public void setPinHash(String hash) {
        prefs.edit().putString(KEY_PIN_HASH, hash).apply();
    }

    public void clearPin() {
        prefs.edit()
                .remove(KEY_PIN_HASH)
                .putBoolean(KEY_PIN_ENABLED, false)
                .apply();
    }

    // =========================
    // THEME
    // =========================

    public String getTheme() {
        return prefs.getString(KEY_THEME, "system");
    }

    public void setTheme(String theme) {
        prefs.edit().putString(KEY_THEME, theme).apply();
    }

    // =========================
    // INDEXING
    // =========================

    public long getLastIndexedAt() {
        return prefs.getLong(KEY_LAST_INDEXED_AT, 0L);
    }

    public void setLastIndexedAt(long timestamp) {
        prefs.edit().putLong(KEY_LAST_INDEXED_AT, timestamp).apply();
    }

    // =========================
    // SORT AND FILTER
    // =========================

    public String getSortOrder() {
        return prefs.getString(KEY_SORT_ORDER, "recent");
    }

    public void setSortOrder(String sortOrder) {
        prefs.edit().putString(KEY_SORT_ORDER, sortOrder).apply();
    }

    public String getFilterFileType() {
        return prefs.getString(KEY_FILTER_FILE_TYPE, "all");
    }

    public void setFilterFileType(String fileType) {
        prefs.edit().putString(KEY_FILTER_FILE_TYPE, fileType).apply();
    }

    // =========================
    // CLEAR ALL
    // =========================

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
