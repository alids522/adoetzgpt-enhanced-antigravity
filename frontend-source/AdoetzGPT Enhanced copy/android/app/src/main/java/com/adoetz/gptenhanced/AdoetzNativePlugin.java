package com.adoetz.gptenhanced;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.WebView;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "AdoetzNative",
    permissions = {
        @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "microphone"),
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications")
    }
)
public class AdoetzNativePlugin extends Plugin {
    private static final String PREFS_NAME = "adoetzgpt_native";
    private static final String SERVER_URL_KEY = "server_url";

    private SharedPreferences getPrefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void setServerUrl(PluginCall call) {
        String url = call.getString("url", "");
        getPrefs().edit().putString(SERVER_URL_KEY, url).apply();

        JSObject result = new JSObject();
        result.put("url", url);
        call.resolve(result);
    }

    @PluginMethod
    public void getServerUrl(PluginCall call) {
        JSObject result = new JSObject();
        result.put("url", getPrefs().getString(SERVER_URL_KEY, ""));
        call.resolve(result);
    }

    @PluginMethod
    public void clearServerUrl(PluginCall call) {
        getPrefs().edit().remove(SERVER_URL_KEY).apply();
        call.resolve();
    }

    @PluginMethod
    public void clearWebViewCache(PluginCall call) {
        WebView webView = getBridge().getWebView();
        if (webView == null) {
            call.resolve();
            return;
        }

        webView.post(() -> {
            webView.clearCache(true);
            call.resolve();
        });
    }

    @PluginMethod
    public void startLiveVoice(PluginCall call) {
        if (!hasPermission("microphone")) {
            requestPermissionForAlias("microphone", call, "voicePermissionCallback");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission("notifications")) {
            requestPermissionForAlias("notifications", call, "voicePermissionCallback");
            return;
        }

        startVoiceService(call);
    }

    @PermissionCallback
    private void voicePermissionCallback(PluginCall call) {
        if (!hasPermission("microphone")) {
            call.reject("Microphone permission is required for live voice.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission("notifications")) {
            requestPermissionForAlias("notifications", call, "voicePermissionCallback");
            return;
        }

        startVoiceService(call);
    }

    private void startVoiceService(PluginCall call) {
        String serverUrl = call.getString("serverUrl", getPrefs().getString(SERVER_URL_KEY, ""));
        String sessionId = call.getString("sessionId", "");
        String chatId = call.getString("chatId", "");
        String model = call.getString("model", "");
        boolean captureAudio = Boolean.TRUE.equals(call.getBoolean("captureAudio", false));

        Intent intent = new Intent(getContext(), LiveVoiceForegroundService.class);
        intent.setAction(LiveVoiceForegroundService.ACTION_START);
        intent.putExtra(LiveVoiceForegroundService.EXTRA_SERVER_URL, serverUrl);
        intent.putExtra(LiveVoiceForegroundService.EXTRA_SESSION_ID, sessionId);
        intent.putExtra(LiveVoiceForegroundService.EXTRA_CHAT_ID, chatId);
        intent.putExtra(LiveVoiceForegroundService.EXTRA_MODEL, model);
        intent.putExtra(LiveVoiceForegroundService.EXTRA_CAPTURE_AUDIO, captureAudio);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }

        JSObject result = new JSObject();
        result.put("running", true);
        result.put("serverUrl", serverUrl);
        result.put("sessionId", sessionId);
        result.put("chatId", chatId);
        result.put("model", model);
        result.put("captureAudio", captureAudio);
        call.resolve(result);
    }

    @PluginMethod
    public void stopLiveVoice(PluginCall call) {
        Intent intent = new Intent(getContext(), LiveVoiceForegroundService.class);
        intent.setAction(LiveVoiceForegroundService.ACTION_STOP);
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void isLiveVoiceRunning(PluginCall call) {
        JSObject result = new JSObject();
        result.put("running", LiveVoiceForegroundService.isRunning());
        call.resolve(result);
    }
}
