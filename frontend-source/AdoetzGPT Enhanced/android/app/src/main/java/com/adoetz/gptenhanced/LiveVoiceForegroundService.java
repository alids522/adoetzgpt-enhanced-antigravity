package com.adoetz.gptenhanced;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class LiveVoiceForegroundService extends Service {
    public static final String ACTION_START = "com.adoetz.gptenhanced.action.START_LIVE_VOICE";
    public static final String ACTION_STOP = "com.adoetz.gptenhanced.action.STOP_LIVE_VOICE";
    public static final String EXTRA_SERVER_URL = "server_url";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_CHAT_ID = "chat_id";
    public static final String EXTRA_MODEL = "model";
    public static final String EXTRA_CAPTURE_AUDIO = "capture_audio";

    private static final String CHANNEL_ID = "adoetzgpt_live_voice";
    private static final int NOTIFICATION_ID = 4201;
    private static final int SAMPLE_RATE = 16000;

    private static volatile boolean running = false;

    private AudioRecord audioRecord;
    private AudioFocusRequest audioFocusRequest;
    private AudioManager audioManager;
    private Thread captureThread;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String serverUrl = "";
    private String sessionId = "";
    private String chatId = "";
    private String model = "";
    private boolean captureAudio = false;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopLiveVoice();
            stopSelf();
            return START_NOT_STICKY;
        }

        serverUrl = intent != null ? intent.getStringExtra(EXTRA_SERVER_URL) : "";
        sessionId = intent != null ? intent.getStringExtra(EXTRA_SESSION_ID) : "";
        chatId = intent != null ? intent.getStringExtra(EXTRA_CHAT_ID) : "";
        model = intent != null ? intent.getStringExtra(EXTRA_MODEL) : "";
        captureAudio = intent != null && intent.getBooleanExtra(EXTRA_CAPTURE_AUDIO, false);

        startAsForeground();
        startLiveVoice();
        return START_STICKY;
    }

    private void startLiveVoice() {
        if (running) return;
        if (captureAudio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        acquireWakeLocks();
        requestAudioFocus();

        running = true;

        if (!captureAudio) {
            return;
        }

        int minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = Math.max(minBufferSize, SAMPLE_RATE * 2);

        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        );

        audioRecord.startRecording();

        captureThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            while (running && audioRecord != null) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // TODO: Stream PCM frames to the OpenWebUI/Gemini Live transport.
                    // The foreground service owns background-safe audio capture; the
                    // network bridge can be wired to the modified backend endpoint once
                    // the Android-facing live transport contract is finalized.
                }
            }
        }, "AdoetzGPT-LiveVoiceCapture");
        captureThread.start();
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void acquireWakeLocks() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdoetzGPT:LiveVoice");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        }

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AdoetzGPT:LiveVoiceWifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void requestAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChange -> {
                })
                .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(
                focusChange -> {
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            );
        }
    }

    private void stopLiveVoice() {
        running = false;

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }

        if (captureThread != null) {
            try {
                captureThread.join(750);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;

        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;

        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                audioManager.abandonAudioFocus(null);
            }
        }
        audioFocusRequest = null;
        audioManager = null;
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, LiveVoiceForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String activeModel = model == null || model.isEmpty() ? "Gemini Live" : model;
        String text = serverUrl == null || serverUrl.isEmpty()
            ? activeModel + " session is active"
            : activeModel + " connected to " + serverUrl;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle("AdoetzGPT Enhanced is listening")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Live voice",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps Gemini Live voice sessions active in the background.");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (running) {
            startAsForeground();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopLiveVoice();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
