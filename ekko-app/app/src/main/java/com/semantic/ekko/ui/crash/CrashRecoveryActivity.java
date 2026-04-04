package com.semantic.ekko.ui.crash;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.semantic.ekko.R;
import com.semantic.ekko.ui.launcher.LauncherActivity;

public class CrashRecoveryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash_recovery);

        findViewById(R.id.btnRecoverRestart).setOnClickListener(v -> restartApp());
        findViewById(R.id.btnRecoverClose).setOnClickListener(v -> finishAffinity());
    }

    private void restartApp() {
        Intent intent = new Intent(this, LauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
