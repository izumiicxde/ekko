package com.semantic.ekko.ui.launcher;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.semantic.ekko.R;
import com.semantic.ekko.ui.main.MainActivity;
import com.semantic.ekko.ui.onboarding.OnboardingActivity;
import com.semantic.ekko.util.PrefsManager;

public class LauncherActivity extends AppCompatActivity {

    private static final long ROUTE_DELAY_MS = 1050L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        applyInsets();
        playEntranceAnimation();

        PrefsManager prefsManager = new PrefsManager(this);
        Class<?> target = prefsManager.isOnboardingDone()
            ? MainActivity.class
            : OnboardingActivity.class;

        new Handler(Looper.getMainLooper()).postDelayed(
            () -> {
                Intent intent = new Intent(this, target);
                startActivity(intent);
                finish();
            },
            ROUTE_DELAY_MS
        );
    }

    private void applyInsets() {
        View root = findViewById(R.id.launcherRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            );
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            );
            return insets;
        });
    }

    private void playEntranceAnimation() {
        View markWrap = findViewById(R.id.launcherMarkWrap);
        TextView title = findViewById(R.id.txtLauncherTitle);
        TextView subtitle = findViewById(R.id.txtLauncherSubtitle);
        TextView caption = findViewById(R.id.txtLauncherCaption);

        markWrap.setAlpha(0f);
        title.setAlpha(0f);
        subtitle.setAlpha(0f);
        caption.setAlpha(0f);

        markWrap.setScaleX(0.92f);
        markWrap.setScaleY(0.92f);
        title.setTranslationY(14f);
        subtitle.setTranslationY(18f);
        caption.setTranslationY(22f);

        AnimatorSet intro = new AnimatorSet();
        intro.playTogether(
            ObjectAnimator.ofFloat(markWrap, View.ALPHA, 0f, 1f),
            ObjectAnimator.ofFloat(markWrap, View.SCALE_X, 0.92f, 1f),
            ObjectAnimator.ofFloat(markWrap, View.SCALE_Y, 0.92f, 1f),
            ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f),
            ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, 14f, 0f),
            ObjectAnimator.ofFloat(subtitle, View.ALPHA, 0f, 1f),
            ObjectAnimator.ofFloat(subtitle, View.TRANSLATION_Y, 18f, 0f),
            ObjectAnimator.ofFloat(caption, View.ALPHA, 0f, 1f),
            ObjectAnimator.ofFloat(caption, View.TRANSLATION_Y, 22f, 0f)
        );
        intro.setDuration(620L);
        intro.setInterpolator(new AccelerateDecelerateInterpolator());
        intro.start();

        ObjectAnimator floatMark = ObjectAnimator.ofFloat(
            markWrap,
            View.TRANSLATION_Y,
            0f,
            -10f,
            0f
        );
        floatMark.setDuration(1800L);
        floatMark.setRepeatCount(ObjectAnimator.INFINITE);
        floatMark.setInterpolator(new AccelerateDecelerateInterpolator());
        floatMark.start();
    }
}
