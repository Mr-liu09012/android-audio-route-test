package com.example.trtcnativecall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.tencent.trtc.TRTCCloud;
import com.tencent.trtc.TRTCCloudDef;
import com.tencent.trtc.TRTCCloudListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Foreground call service. Keeps the TRTC session and custom audio path alive
 * when MainActivity is in the background.
 */
public class CallService extends Service {
    public static final String ACTION_START = "com.example.trtcnativecall.START";
    public static final String ACTION_STOP = "com.example.trtcnativecall.STOP";
    public static final String EXTRA_INPUT_ID = "input_id";
    public static final String EXTRA_OUTPUT_ID = "output_id";

    // Personal test defaults. Do not ship a production app with a hard-coded UserSig.
    private static final int SDK_APP_ID = 1600167619;
    private static final int ROOM_ID = 123456;
    private static final String USER_ID = "And";
    private static final String USER_SIG =
            "eJyrVgrxCdYrSy1SslIy0jNQ0gHzM1NS80oy0zLBwo55KVDh4pTsxIKCzBQlK0MzAwNDM3MzQ0uITGpFQWZRKlDc1NTUyMDAACJakpkLEjO3NDIyNTI1t4CakpkONDXfxNDVJTQpOMPCJLvCODEyPSUtKrLMucLZ29vI18cyJcA-J8Tb2DXfqzTUVqkWAIt7MCc_";

    private static final String CHANNEL_ID = "trtc_call";
    private static final int NOTIFICATION_ID = 1001;
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_BYTES = 1920; // 20 ms, mono, 16-bit

    private final IBinder binder = new LocalBinder();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private TRTCCloud trtc;
    private TRTCCloudListener listener;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread captureThread;
    private Thread renderThread;
    private AudioRecord.OnRoutingChangedListener recordRouteListener;
    private AudioTrack.OnRoutingChangedListener trackRouteListener;

    private volatile boolean running = false;
    private volatile boolean joined = false;
    private volatile boolean publishing = false;
    private volatile boolean renderRunning = false;
    private volatile int localLevel = 0;
    private volatile int remoteLevel = 0;
    private volatile String inputRoute = "—";
    private volatile String outputRoute = "—";
    private volatile String status = "未通话";
    private volatile String lastError = null;
    private int selectedInputId = -1;
    private int selectedOutputId = -1;
    private String selectedInputLabel = "—";
    private String selectedOutputLabel = "—";

    public class LocalBinder extends Binder {
        public CallService getService() { return CallService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCall("通知栏挂断");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            if (!running) {
                startCall(intent.getIntExtra(EXTRA_INPUT_ID, -1),
                        intent.getIntExtra(EXTRA_OUTPUT_ID, -1));
            }
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startCall(int inputId, int outputId) {
        if (running) return;
        running = true;
        lastError = null;
        joined = false;
        publishing = false;
        renderRunning = false;
        localLevel = 0;
        remoteLevel = 0;
        selectedInputId = inputId;
        selectedOutputId = outputId;
        status = "正在连接…";

        try {
            startForegroundCompat(buildNotification("正在连接 TRTC 房间…"));
        } catch (Exception e) {
            running = false;
            lastError = "前台服务启动失败：" + e;
            status = "失败";
            stopSelf();
            return;
        }
        acquireWakeLock();

        try {
            AudioDeviceInfo input = findDevice(inputId, true);
            AudioDeviceInfo output = findDevice(outputId, false);
            if (input == null) input = findType(true, AudioDeviceInfo.TYPE_BUILTIN_MIC);
            if (output == null) output = findType(false, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
            if (output == null) output = findType(false, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            if (input == null || output == null) {
                throw new IllegalStateException("找不到可用的输入/输出设备");
            }
            selectedInputId = input.getId();
            selectedOutputId = output.getId();
            selectedInputLabel = deviceLabel(input);
            selectedOutputLabel = deviceLabel(output);

            audioManager.setMode(AudioManager.MODE_NORMAL);
            createAudioRecord(input);
            createAudioTrack(output);

            trtc = TRTCCloud.sharedInstance(getApplicationContext());
            listener = new Listener();
            trtc.addListener(listener);

            // Must be set before enterRoom.
            trtc.enableCustomAudioCapture(true);
            trtc.enableCustomAudioRendering(true);

            TRTCCloudDef.TRTCParams params = new TRTCCloudDef.TRTCParams();
            params.sdkAppId = SDK_APP_ID;
            params.userId = USER_ID;
            params.userSig = USER_SIG;
            params.roomId = ROOM_ID;
            params.role = TRTCCloudDef.TRTCRoleAnchor;

            trtc.enterRoom(params, TRTCCloudDef.TRTC_APP_SCENE_AUDIOCALL);
            updateNotification("正在加入房间…");
            Log.i("TrtcCall", "enterRoom room=" + ROOM_ID + " user=" + USER_ID
                    + " input=" + selectedInputLabel + " output=" + selectedOutputLabel);
        } catch (Exception e) {
            fail("启动失败：" + e);
        }
    }

    private void onJoined() {
        joined = true;
        status = "通话中";
        try {
            trtc.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_SPEECH);
        } catch (Exception e) {
            lastError = e.toString();
            Log.e("TrtcCall", "startLocalAudio", e);
        }
        try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}

        // TRTC may re-apply its audio mode after room entry; declare device preference again.
        try {
            if (audioRecord != null && selectedInputId >= 0) {
                AudioDeviceInfo in = findDevice(selectedInputId, true);
                if (in != null) audioRecord.setPreferredDevice(in);
            }
            if (audioTrack != null && selectedOutputId >= 0) {
                AudioDeviceInfo out = findDevice(selectedOutputId, false);
                if (out != null) audioTrack.setPreferredDevice(out);
            }
        } catch (Exception e) {
            Log.w("TrtcCall", "re-apply preferred device failed", e);
        }

        startAudioThreads();
        updateRoutes("entered");
        updateNotification("通话中");
        ui.postDelayed(new Runnable() {
            @Override public void run() { updateRoutes("entered+1s"); }
        }, 1000);
    }

    private void createAudioRecord(AudioDeviceInfo input) {
        int min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(Math.max(min, FRAME_BYTES * 4), 8192);
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord 初始化失败，state=" + audioRecord.getState());
        }
        audioRecord.setPreferredDevice(input);
    }

    private void createAudioTrack(AudioDeviceInfo output) {
        int min = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(Math.max(min, FRAME_BYTES * 6), 12288);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(buf)
                .build();
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioTrack 初始化失败，state=" + audioTrack.getState());
        }
        audioTrack.setPreferredDevice(output);
        audioTrack.setVolume(0.75f);
    }

    private void startAudioThreads() {
        if (audioRecord == null || audioTrack == null) return;
        publishing = true;
        try { audioRecord.startRecording(); } catch (Exception e) { Log.e("TrtcCall", "startRecording", e); }
        try { audioTrack.play(); } catch (Exception e) { Log.e("TrtcCall", "play", e); }
        installRoutingListeners();

        captureThread = new Thread(new Runnable() {
            @Override public void run() { captureLoop(); }
        }, "trtc-capture");
        captureThread.start();

        renderThread = new Thread(new Runnable() {
            @Override public void run() { renderLoop(); }
        }, "trtc-render");
        renderThread.start();
    }

    private void captureLoop() {
        byte[] buffer = new byte[FRAME_BYTES];
        while (publishing && audioRecord != null && trtc != null) {
            int n;
            try {
                n = audioRecord.read(buffer, 0, buffer.length);
            } catch (Exception e) {
                lastError = "AudioRecord.read: " + e;
                break;
            }
            if (n <= 0) continue;
            try {
                TRTCCloudDef.TRTCAudioFrame frame = new TRTCCloudDef.TRTCAudioFrame();
                frame.data = Arrays.copyOf(buffer, n);
                frame.sampleRate = SAMPLE_RATE;
                frame.channel = 1;
                frame.timestamp = trtc.generateCustomPTS();
                trtc.sendCustomAudioData(frame);
                localLevel = rmsLevel(frame.data);
            } catch (Exception e) {
                lastError = "sendCustomAudioData: " + e;
                Log.e("TrtcCall", "sendCustomAudioData", e);
                break;
            }
        }
    }

    private void renderLoop() {
        byte[] buffer = new byte[FRAME_BYTES];
        TRTCCloudDef.TRTCAudioFrame frame = new TRTCCloudDef.TRTCAudioFrame();
        frame.data = buffer;
        frame.sampleRate = SAMPLE_RATE;
        frame.channel = 1;
        renderRunning = true;
        while (renderRunning && audioTrack != null && trtc != null) {
            try {
                trtc.getCustomAudioRenderingFrame(frame);
                byte[] out = frame.data != null ? frame.data : buffer;
                int written = audioTrack.write(out, 0, out.length);
                if (written < 0) {
                    lastError = "AudioTrack.write " + written;
                    break;
                }
                remoteLevel = rmsLevel(out);
            } catch (Exception e) {
                lastError = "render: " + e;
                Log.e("TrtcCall", "render", e);
                break;
            }
        }
        renderRunning = false;
    }

    private void installRoutingListeners() {
        if (audioRecord != null) {
            recordRouteListener = new AudioRecord.OnRoutingChangedListener() {
                @Override public void onRoutingChanged(AudioRecord recorder) {
                    ui.post(new Runnable() {
                        @Override public void run() { updateRoutes("input-routing-changed"); }
                    });
                }
            };
            audioRecord.addOnRoutingChangedListener(recordRouteListener, ui);
        }
        if (audioTrack != null) {
            trackRouteListener = new AudioTrack.OnRoutingChangedListener() {
                @Override public void onRoutingChanged(AudioTrack track) {
                    ui.post(new Runnable() {
                        @Override public void run() { updateRoutes("output-routing-changed"); }
                    });
                }
            };
            audioTrack.addOnRoutingChangedListener(trackRouteListener, ui);
        }
    }

    private void removeRoutingListeners() {
        try {
            if (audioRecord != null && recordRouteListener != null) {
                audioRecord.removeOnRoutingChangedListener(recordRouteListener);
            }
        } catch (Exception ignored) {}
        try {
            if (audioTrack != null && trackRouteListener != null) {
                audioTrack.removeOnRoutingChangedListener(trackRouteListener);
            }
        } catch (Exception ignored) {}
        recordRouteListener = null;
        trackRouteListener = null;
    }

    private void updateRoutes(String reason) {
        try {
            if (audioRecord != null) {
                AudioDeviceInfo d = audioRecord.getRoutedDevice();
                if (d != null) inputRoute = deviceLabel(d);
            }
        } catch (Exception ignored) {}
        try {
            if (audioTrack != null) {
                AudioDeviceInfo d = audioTrack.getRoutedDevice();
                if (d != null) outputRoute = deviceLabel(d);
            }
        } catch (Exception ignored) {}
        updateNotification(null);
        Log.i("TrtcCall", "route(" + reason + ") in=" + inputRoute + " out=" + outputRoute);
    }

    private void stopCall(String reason) {
        boolean wasRunning = running;
        running = false;
        joined = false;
        publishing = false;
        renderRunning = false;
        status = "未通话";

        if (trtc != null) {
            try { trtc.stopLocalAudio(); } catch (Exception ignored) {}
            try { trtc.exitRoom(); } catch (Exception ignored) {}
        }
        joinThreads();
        releaseAudio();
        cleanupTrtc();
        releaseWakeLock();
        if (wasRunning) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) {}
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.cancel(NOTIFICATION_ID);
            } catch (Exception ignored) {}
        }
        Log.i("TrtcCall", "stopCall: " + reason);
    }

    private void fail(String message) {
        lastError = message;
        status = "失败";
        Log.e("TrtcCall", message);
        updateNotification(message);
        stopCall(message);
        stopSelf();
    }

    private void joinThreads() {
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            try { t.join(800); } catch (InterruptedException ignored) {}
        }
        t = renderThread;
        renderThread = null;
        if (t != null) {
            try { t.join(800); } catch (InterruptedException ignored) {}
        }
    }

    private void releaseAudio() {
        removeRoutingListeners();
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (Exception ignored) {}
            try { audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    private void cleanupTrtc() {
        if (trtc != null) {
            try { if (listener != null) trtc.removeListener(listener); } catch (Exception ignored) {}
            try { TRTCCloud.destroySharedInstance(); } catch (Exception ignored) {}
            trtc = null;
            listener = null;
        }
    }

    private class Listener extends TRTCCloudListener {
        @Override
        public void onEnterRoom(long result) {
            Log.i("TrtcCall", "onEnterRoom " + result);
            if (result > 0) {
                onJoined();
            } else {
                fail("进房失败：" + result);
            }
        }

        @Override
        public void onExitRoom(int reason) {
            Log.i("TrtcCall", "onExitRoom " + reason);
            joined = false;
        }

        @Override
        public void onRemoteUserEnterRoom(String uid) {
            updateNotification("对方已进入");
        }

        @Override
        public void onRemoteUserLeaveRoom(String uid, int reason) {
            updateNotification("对方已离开");
        }

        @Override
        public void onUserAudioAvailable(String uid, boolean available) {
            updateNotification(available ? "对方麦克风已开" : "对方麦克风已关");
        }

        @Override
        public void onError(int errCode, String errMsg, Bundle extraInfo) {
            lastError = "TRTC " + errCode + "：" + errMsg;
            Log.e("TrtcCall", "TRTC error " + errCode + " " + errMsg);
            status = "TRTC 错误";
            updateNotification("TRTC 错误 " + errCode);
        }

        @Override
        public void onWarning(int warningCode, String warningMsg, Bundle extraInfo) {
            Log.w("TrtcCall", "TRTC warning " + warningCode + " " + warningMsg);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TRTC 通话", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("通话期间保持后台运行");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, CallService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String routeText = inputRoute + " → " + outputRoute;
        b.setContentTitle("TRTC 通话中")
                .setContentText(text == null ? routeText : text)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "挂断", stopPending);
        if (Build.VERSION.SDK_INT >= 26) b.setColor(Color.rgb(30, 120, 220));
        return b.build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        if (!running) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrtcCall::Call");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10L * 60L * 60L * 1000L);
        } catch (Exception e) {
            Log.w("TrtcCall", "wakeLock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    private AudioDeviceInfo findDevice(int id, boolean source) {
        if (id < 0) return null;
        try {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL);
            for (AudioDeviceInfo d : devices) {
                if (d.getId() == id && (source ? d.isSource() : d.isSink())) return d;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private AudioDeviceInfo findType(boolean source, int type) {
        try {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL);
            for (AudioDeviceInfo d : devices) {
                if ((source ? d.isSource() : d.isSink()) && d.getType() == type) return d;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String deviceLabel(AudioDeviceInfo d) {
        if (d == null) return "—";
        String name;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: name = "内置麦克风"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: name = "蓝牙 A2DP"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: name = "蓝牙 SCO"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: name = "内置扬声器"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: name = "内置听筒"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: name = "有线耳麦"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: name = "有线耳机"; break;
            case AudioDeviceInfo.TYPE_USB_HEADSET: name = "USB 耳麦"; break;
            default: name = "类型 " + d.getType();
        }
        return name + " | " + d.getProductName() + " | id=" + d.getId();
    }

    private static int rmsLevel(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return 0;
        long sum = 0;
        int samples = pcm.length / 2;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = (short) ((pcm[i] & 0xff) | ((pcm[i + 1] & 0xff) << 8));
            sum += (long) v * v;
        }
        double rms = Math.sqrt((double) sum / Math.max(1, samples));
        return Math.max(0, Math.min(100, (int) (rms / 32768.0 * 100.0 * 3.0)));
    }

    public boolean isRunning() { return running; }
    public boolean isJoined() { return joined; }
    public String getStatus() { return status; }
    public String getInputRoute() { return inputRoute; }
    public String getOutputRoute() { return outputRoute; }
    public String getSelectedInput() { return selectedInputLabel; }
    public String getSelectedOutput() { return selectedOutputLabel; }
    public int getLocalLevel() { return localLevel; }
    public int getRemoteLevel() { return remoteLevel; }
    public String getLastError() { return lastError; }

    @Override
    public void onDestroy() {
        stopCall("service onDestroy");
        super.onDestroy();
    }
}
