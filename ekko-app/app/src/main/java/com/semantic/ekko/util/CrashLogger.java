package com.semantic.ekko.util;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLogger {

    private static final String TAG = "CrashLogger";
    private static final String CRASH_DIR = "crash_logs";
    private static final String LATEST_FILE = "latest_crash.txt";

    private CrashLogger() {}

    public static void install(Context context) {
        if (context == null) {
            return;
        }
        Thread.UncaughtExceptionHandler previous =
            Thread.getDefaultUncaughtExceptionHandler();
        Context appContext = context.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrash(appContext, thread, throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
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
        } catch (Exception e) {
            Log.e(TAG, "Failed to write crash log", e);
        }
    }
}
