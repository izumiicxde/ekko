package com.semantic.ekko.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;

public final class NotificationPermissionHelper {

    private NotificationPermissionHelper() {}

    public static boolean shouldRequestNotificationPermission(Context context) {
        return (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context != null &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        );
    }
}
