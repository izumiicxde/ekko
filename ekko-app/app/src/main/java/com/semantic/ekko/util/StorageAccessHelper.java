package com.semantic.ekko.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StorageAccessHelper {

    private StorageAccessHelper() {}

    public static boolean supportsAllFilesAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static boolean hasAllFilesAccess() {
        return !supportsAllFilesAccess() || Environment.isExternalStorageManager();
    }

    public static Intent createManageAllFilesAccessIntent(Context context) {
        Intent appIntent = new Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + context.getPackageName())
        );
        appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return appIntent;
    }

    public static List<File> discoverAccessiblePublicFolders(Context context) {
        Map<String, File> folders = new LinkedHashMap<>();
        File[] appExternalDirs = context.getExternalFilesDirs(null);
        if (appExternalDirs == null) return new ArrayList<>();

        for (File appExternalDir : appExternalDirs) {
            File storageRoot = resolveStorageRoot(appExternalDir);
            if (storageRoot == null || !storageRoot.canRead()) continue;

            File[] children = storageRoot.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (!shouldIncludeTopLevelFolder(child)) continue;
                folders.put(child.getAbsolutePath(), child);
            }
        }

        return new ArrayList<>(folders.values());
    }

    public static String getFolderDisplayName(File folder) {
        if (folder == null) return "Shared storage";
        String name = folder.getName();
        if (name != null && !name.trim().isEmpty()) return name;
        return folder.getAbsolutePath();
    }

    private static File resolveStorageRoot(File appExternalDir) {
        if (appExternalDir == null) return null;
        String path = appExternalDir.getAbsolutePath();
        String marker = File.separator + "Android" + File.separator + "data";
        int markerIndex = path.indexOf(marker);
        if (markerIndex <= 0) return null;
        return new File(path.substring(0, markerIndex));
    }

    private static boolean shouldIncludeTopLevelFolder(File folder) {
        if (folder == null || !folder.isDirectory() || !folder.canRead()) {
            return false;
        }
        String name = folder.getName();
        if (name == null || name.trim().isEmpty()) return false;
        if (name.startsWith(".")) return false;
        return (
            !"Android".equalsIgnoreCase(name) &&
            !"data".equalsIgnoreCase(name) &&
            !"obb".equalsIgnoreCase(name)
        );
    }
}
