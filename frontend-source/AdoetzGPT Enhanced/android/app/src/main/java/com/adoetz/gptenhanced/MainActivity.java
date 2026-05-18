package com.adoetz.gptenhanced;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AdoetzNativePlugin.class);
        super.onCreate(savedInstanceState);

        WebSettings settings = getBridge().getWebView().getSettings();
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        configureSystemBars();
        keepWebViewActiveIfLiveVoiceRunning();
    }

    private void configureSystemBars() {
        Window window = getWindow();
        WebView webView = getBridge().getWebView();
        View contentView = findViewById(android.R.id.content);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.rgb(17, 17, 17));
            window.setNavigationBarColor(Color.rgb(17, 17, 17));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            window.setAttributes(layoutParams);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        }

        webView.setClipToPadding(false);
        webView.setFitsSystemWindows(true);
        if (contentView != null) {
            contentView.setFitsSystemWindows(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.getDecorView().setOnApplyWindowInsetsListener((View view, WindowInsets insets) -> {
                int left;
                int top;
                int right;
                int bottom;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets systemBars = insets.getInsets(
                        WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars()
                            | WindowInsets.Type.displayCutout()
                    );
                    left = systemBars.left;
                    top = systemBars.top;
                    right = systemBars.right;
                    bottom = systemBars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                        top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                        left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                        right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                    }
                }

                webView.setPadding(left, top, right, bottom);
                return insets;
            });
            window.getDecorView().requestApplyInsets();
        }
    }

    private void keepWebViewActiveIfLiveVoiceRunning() {
        if (!LiveVoiceForegroundService.isRunning()) return;

        WebView webView = getBridge().getWebView();
        webView.onResume();
        webView.resumeTimers();
    }

    @Override
    public void onPause() {
        super.onPause();
        keepWebViewActiveIfLiveVoiceRunning();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWebViewActiveIfLiveVoiceRunning();
    }

    @Override
    public void onResume() {
        super.onResume();
        configureSystemBars();
        keepWebViewActiveIfLiveVoiceRunning();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            configureSystemBars();
        }
    }
}
