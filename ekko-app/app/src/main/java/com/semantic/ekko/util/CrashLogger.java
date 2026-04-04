package com.semantic.ekko.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import com.semantic.ekko.ui.crash.CrashRecoveryActivity;

public final class CrashLogger {

    private static final String CRASH_DIR = "crash_logs";
    private static final String LATEST_FILE = "latest_crash.txt";
    private static volatile boolean handlingCrash = false;

    private CrashLogger() {}

    public static void install(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (handlingCrash) {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
                return;
            }
            handlingCrash = true;
            writeCrash(appContext, thread, throwable);
            scheduleRecovery(appContext);
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }

    public static void logHandled(Context context, String label, Throwable t) {
        if (context == null || t == null) {
            return;
        }
        writeCrash(context.getApplicationContext(), Thread.currentThread(), t, label);
    }

    private static void writeCrash(
        Context context,
        Thread thread,
        Throwable throwable
    ) {
        writeCrash(context, thread, throwable, "Uncaught");
    }

    private static void writeCrash(
        Context context,
        Thread thread,
        Throwable throwable,
        String label
    ) {
        try {
            File dir = new File(context.getFilesDir(), CRASH_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File latest = new File(dir, LATEST_FILE);
            StringWriter stackWriter = new StringWriter();
            PrintWriter printer = new PrintWriter(stackWriter);
            throwable.printStackTrace(printer);
            printer.flush();

            String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US
            )
                .format(new Date());

            try (FileWriter writer = new FileWriter(latest, false)) {
                writer.write("label: " + label + "\n");
                writer.write("time: " + timestamp + "\n");
                writer.write(
                    "thread: " +
                    (thread != null ? thread.getName() : "unknown") +
                    "\n\n"
                );
                writer.write(stackWriter.toString());
            }
        } catch (Exception ignored) {
            // no-op
        }
    }

    private static void scheduleRecovery(Context context) {
        Intent intent = new Intent(context, CrashRecoveryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            404,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 160L, pendingIntent);
        }
    }
}
