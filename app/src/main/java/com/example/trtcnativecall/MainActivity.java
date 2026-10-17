package com.example.trtcnativecall;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.trtc.TRTCCloud;
import com.tencent.trtc.TRTCCloudDef;
import com.tencent.trtc.TRTCCloudListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Native two-party TRTC audio call with custom audio capture/rendering.
 *
 * Audio path:
 *   AudioRecord (built-in mic) -> TRTC sendCustomAudioData()
 *   TRTC getCustomAudioRenderingFrame() -> AudioTrack (USAGE_MEDIA -> Bluetooth A2DP)
 *
 * This avoids TRTC's internal player forcing the communication/speaker route.
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 43;
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_BYTES = 1920; // 20 ms, mono, 16-bit

    private AudioManager audioManager;
    private EditText sdkAppIdInput;
    private EditText roomIdInput;
    private EditText userIdInput;
    private EditText userSigInput;
    private Spinner inputSpinner;
    private Spinner outputSpinner;
    private Button refreshButton;
    private Button joinButton;
    private Button leaveButton;
    private Button copyButton;
    private Button shareButton;
    private TextView statusText;
    private TextView routeText;
    private TextView localLevelText;
    private TextView remoteLevelText;
    private TextView logText;

    private final List<DeviceItem> inputItems = new ArrayList<>();
    private final List<DeviceItem> outputItems = new ArrayList<>();
    private final List<String> remoteUsers = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final List<String> routeChanges = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TRTCCloud trtc;
    private TRTCCloudListener listener;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread captureThread;
    private Thread renderThread;
    private volatile boolean joined = false;
    private volatile boolean publishing = false;
    private volatile boolean renderRunning = false;
    private int sdkAppId = 0;
    private int roomId = 0;
    private String userId = "";
    private String lastError = null;
    private String lastInputRoute = "—";
    private String lastOutputRoute = "—";
    private String selectedInputLabel = "—";
    private String selectedOutputLabel = "—";
    private AudioDeviceInfo selectedInputDevice;
    private AudioDeviceInfo selectedOutputDevice;
    private int localLevel = 0;
    private int remoteLevel = 0;
    private long lastLevelUiAt = 0;

    private AudioRecord.OnRoutingChangedListener recordRouteListener;
    private AudioTrack.OnRoutingChangedListener trackRouteListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        buildUi();
        refreshDevices();
        log("应用启动。请连接蓝牙耳机，填入 TRTC 凭据，选择内置麦克风 + 蓝牙 A2DP，然后加入房间。");
        log("本 App 使用自定义音频采集/渲染：不经 TRTC 内部播放器，输出可指定 AudioTrack 设备。");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        root.addView(text("TRTC Native Call", 24, true));
        TextView sub = text("原生双人音频通话 · 自定义音频采集/渲染", 13, false);
        sub.setTextColor(Color.rgb(80, 92, 110));
        sub.setPadding(0, dp(4), 0, dp(10));
        root.addView(sub);

        TextView warning = text(
                "加入房间后会产生真实通话并播放对方声音。请先戴耳机、降低音量；测试时不要同时开其他会议/录音 App。",
                12, false);
        warning.setTextColor(Color.rgb(150, 85, 0));
        warning.setBackgroundColor(Color.rgb(255, 244, 215));
        warning.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(warning, matchWrap());

        addLabel(root, "SDKAppID");
        sdkAppIdInput = edit("数字，例如 1600167619", false);
        root.addView(sdkAppIdInput, matchWrap());

        addLabel(root, "RoomId（数字）");
        roomIdInput = edit("例如 123456", false);
        root.addView(roomIdInput, matchWrap());

        addLabel(root, "UserId");
        userIdInput = edit("例如 phone_test", false);
        root.addView(userIdInput, matchWrap());

        addLabel(root, "UserSig（只在本页内存，不写入报告）");
        userSigInput = edit("粘贴 UserSig，不要把 SecretKey 放进来", true);
        userSigInput.setMinLines(3);
        userSigInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        root.addView(userSigInput, matchWrap());

        addLabel(root, "输入麦克风");
        inputSpinner = new Spinner(this);
        root.addView(inputSpinner, matchWrap());

        addLabel(root, "输出设备");
        outputSpinner = new Spinner(this);
        root.addView(outputSpinner, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        root.addView(row, matchWrap());
        refreshButton = button("刷新设备");
        row.addView(refreshButton, weightWrap(1));
        copyButton = button("复制 JSON");
        row.addView(copyButton, weightWrap(1));
        shareButton = button("分享 JSON");
        row.addView(shareButton, weightWrap(1));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        root.addView(row2, matchWrap());
        joinButton = button("加入房间");
        row2.addView(joinButton, weightWrap(1));
        leaveButton = button("离开房间");
        leaveButton.setEnabled(false);
        row2.addView(leaveButton, weightWrap(1));

        addLabel(root, "状态");
        statusText = text("未加入房间", 13, false);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setBackgroundColor(Color.rgb(244, 248, 253));
        root.addView(statusText, matchWrap());

        addLabel(root, "实际路由");
        routeText = text("尚未加入房间。", 13, false);
        routeText.setPadding(dp(10), dp(8), dp(10), dp(8));
        routeText.setBackgroundColor(Color.rgb(244, 248, 253));
        root.addView(routeText, matchWrap());

        LinearLayout levelRow = new LinearLayout(this);
        levelRow.setOrientation(LinearLayout.HORIZONTAL);
        levelRow.setPadding(0, dp(8), 0, 0);
        root.addView(levelRow, matchWrap());
        localLevelText = text("本地 0", 12, false);
        localLevelText.setPadding(dp(8), dp(6), dp(8), dp(6));
        localLevelText.setBackgroundColor(Color.rgb(244, 248, 253));
        levelRow.addView(localLevelText, weightWrap(1));
        remoteLevelText = text("远端 0", 12, false);
        remoteLevelText.setPadding(dp(8), dp(6), dp(8), dp(6));
        remoteLevelText.setBackgroundColor(Color.rgb(244, 248, 253));
        levelRow.addView(remoteLevelText, weightWrap(1));

        addLabel(root, "日志");
        logText = text("", 11, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(10), dp(8), dp(10), dp(8));
        logText.setBackgroundColor(Color.rgb(248, 249, 251));
        root.addView(logText, matchWrap());

        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshDevices(); }
        });
        joinButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { joinRoom(); }
        });
        leaveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { leaveRoom("手动离开"); }
        });
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyReport(); }
        });
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareReport(); }
        });
    }

    private EditText edit(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setSingleLine(!multiline);
        if (multiline) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return e;
    }

    private void refreshDevices() {
        try {
            int oldIn = -1, oldOut = -1;
            if (inputSpinner.getSelectedItemPosition() >= 0
                    && inputSpinner.getSelectedItemPosition() < inputItems.size()) {
                oldIn = inputItems.get(inputSpinner.getSelectedItemPosition()).info.getId();
            }
            if (outputSpinner.getSelectedItemPosition() >= 0
                    && outputSpinner.getSelectedItemPosition() < outputItems.size()) {
                oldOut = outputItems.get(outputSpinner.getSelectedItemPosition()).info.getId();
            }

            inputItems.clear();
            outputItems.clear();
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL);
            for (AudioDeviceInfo d : devices) {
                if (d.isSource()) inputItems.add(new DeviceItem(d));
                if (d.isSink()) outputItems.add(new DeviceItem(d));
            }

            ArrayAdapter<DeviceItem> inAdapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, inputItems);
            inAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            inputSpinner.setAdapter(inAdapter);

            ArrayAdapter<DeviceItem> outAdapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, outputItems);
            outAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            outputSpinner.setAdapter(outAdapter);

            int inPos = findById(inputItems, oldIn);
            if (inPos < 0) inPos = findType(inputItems, AudioDeviceInfo.TYPE_BUILTIN_MIC);
            if (inPos < 0 && !inputItems.isEmpty()) inPos = 0;
            if (inPos >= 0) inputSpinner.setSelection(inPos);

            int outPos = findById(outputItems, oldOut);
            if (outPos < 0) outPos = findType(outputItems, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
            if (outPos < 0) outPos = findType(outputItems, AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
            if (outPos < 0) outPos = findType(outputItems, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            if (outPos < 0 && !outputItems.isEmpty()) outPos = 0;
            if (outPos >= 0) outputSpinner.setSelection(outPos);

            log("设备刷新：输入 " + inputItems.size() + "，输出 " + outputItems.size());
            statusText.setText("设备已刷新："
                    + inputItems.size() + " 输入 / " + outputItems.size() + " 输出");
        } catch (Exception e) {
            lastError = e.toString();
            log("刷新设备失败：" + e);
            statusText.setText("刷新设备失败：" + e.getMessage());
        }
    }

    private void joinRoom() {
        if (joined) return;
        if (!ensurePermissions()) return;

        int sdkId;
        int room;
        try {
            sdkId = Integer.parseInt(sdkAppIdInput.getText().toString().trim());
            room = Integer.parseInt(roomIdInput.getText().toString().trim());
        } catch (Exception e) {
            statusText.setText("SDKAppID / RoomId 必须是数字。");
            return;
        }
        String uid = userIdInput.getText().toString().trim();
        String sig = userSigInput.getText().toString().trim();
        if (sdkId <= 0 || room <= 0 || uid.isEmpty() || sig.isEmpty()) {
            statusText.setText("请填写 SDKAppID、RoomId、UserId、UserSig。");
            return;
        }
        AudioDeviceInfo input = selectedInput();
        AudioDeviceInfo output = selectedOutput();
        if (input == null || output == null) {
            statusText.setText("请选择输入麦克风和输出设备。");
            return;
        }

        sdkAppId = sdkId;
        roomId = room;
        userId = uid;
        selectedInputDevice = input;
        selectedOutputDevice = output;
        selectedInputLabel = deviceLabel(input);
        selectedOutputLabel = deviceLabel(output);
        lastError = null;
        routeChanges.clear();
        remoteUsers.clear();
        localLevel = 0;
        remoteLevel = 0;
        updateLevelUi();

        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            createAudioRecord(input);
            createAudioTrack(output);

            trtc = TRTCCloud.sharedInstance(getApplicationContext());
            listener = new Listener();
            trtc.addListener(listener);

            // 必须在 enterRoom 之前开启。
            trtc.enableCustomAudioCapture(true);
            trtc.enableCustomAudioRendering(true);

            TRTCCloudDef.TRTCParams params = new TRTCCloudDef.TRTCParams();
            params.sdkAppId = sdkId;
            params.userId = uid;
            params.userSig = sig;
            params.roomId = room;
            params.role = TRTCCloudDef.TRTCRoleAnchor;

            trtc.enterRoom(params, TRTCCloudDef.TRTC_APP_SCENE_AUDIOCALL);
            statusText.setText("正在加入房间…");
            joinButton.setEnabled(false);
            leaveButton.setEnabled(true);
            log("enterRoom 已调用，等待 onEnterRoom。输出=" + selectedOutputLabel);
        } catch (Exception e) {
            lastError = e.toString();
            log("加入房间失败：" + e);
            statusText.setText("加入失败：" + e.getMessage());
            releaseAudio();
            cleanupTrtc();
        }
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
        boolean ok = audioRecord.setPreferredDevice(input);
        log("AudioRecord 选择输入：" + deviceLabel(input) + "，setPreferredDevice=" + ok);
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
        boolean ok = audioTrack.setPreferredDevice(output);
        audioTrack.setVolume(0.75f);
        log("AudioTrack 选择输出：" + deviceLabel(output)
                + "，setPreferredDevice=" + ok
                + "，usage=USAGE_MEDIA");
    }

    private void startAudioThreads() {
        if (audioRecord == null || audioTrack == null) return;
        publishing = true;
        try { audioRecord.startRecording(); } catch (Exception e) { log("AudioRecord.startRecording 失败：" + e); }
        try { audioTrack.play(); } catch (Exception e) { log("AudioTrack.play 失败：" + e); }
        installRoutingListeners();

        captureThread = new Thread(new Runnable() {
            @Override public void run() { captureLoop(); }
        }, "trtc-capture");
        captureThread.start();

        renderThread = new Thread(new Runnable() {
            @Override public void run() { renderLoop(); }
        }, "trtc-render");
        renderThread.start();

        ui.postDelayed(new Runnable() {
            @Override public void run() { updateRoutes("after-start"); }
        }, 800);
    }

    private void captureLoop() {
        byte[] buffer = new byte[FRAME_BYTES];
        long frames = 0;
        while (publishing && audioRecord != null && trtc != null) {
            int n;
            try {
                n = audioRecord.read(buffer, 0, buffer.length);
            } catch (Exception e) {
                log("AudioRecord.read 异常：" + e);
                break;
            }
            if (n <= 0) {
                if (n < 0) log("AudioRecord.read 失败：" + n);
                continue;
            }
            try {
                byte[] data = Arrays.copyOf(buffer, n);
                TRTCCloudDef.TRTCAudioFrame frame = new TRTCCloudDef.TRTCAudioFrame();
                frame.data = data;
                frame.sampleRate = SAMPLE_RATE;
                frame.channel = 1;
                frame.timestamp = trtc.generateCustomPTS();
                trtc.sendCustomAudioData(frame);
                localLevel = rmsLevel(data);
                frames++;
                if (frames == 1) log("开始投送自定义音频帧，首帧字节=" + n);
                updateLevelUiThrottled();
            } catch (Exception e) {
                log("sendCustomAudioData 失败：" + e);
                break;
            }
        }
        log("采集线程结束");
    }

    private void renderLoop() {
        byte[] buffer = new byte[FRAME_BYTES];
        TRTCCloudDef.TRTCAudioFrame frame = new TRTCCloudDef.TRTCAudioFrame();
        frame.data = buffer;
        frame.sampleRate = SAMPLE_RATE;
        frame.channel = 1;
        renderRunning = true;
        long frames = 0;
        while (renderRunning && audioTrack != null && trtc != null) {
            try {
                trtc.getCustomAudioRenderingFrame(frame);
                byte[] out = frame.data != null ? frame.data : buffer;
                int written = audioTrack.write(out, 0, out.length);
                if (written < 0) {
                    log("AudioTrack.write 失败：" + written);
                    break;
                }
                remoteLevel = rmsLevel(out);
                frames++;
                if (frames == 1) log("开始自定义远端渲染，首帧字节=" + out.length);
                updateLevelUiThrottled();
            } catch (Exception e) {
                log("自定义渲染循环异常：" + e);
                break;
            }
        }
        renderRunning = false;
        log("渲染线程结束");
    }

    private void updateLevelUiThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastLevelUiAt < 180) return;
        lastLevelUiAt = now;
        ui.post(new Runnable() {
            @Override public void run() { updateLevelUi(); }
        });
    }

    private void updateLevelUi() {
        localLevelText.setText("本地 " + localLevel);
        remoteLevelText.setText("远端 " + remoteLevel);
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
        String inputRoute = "—";
        String outputRoute = "—";
        try {
            if (audioRecord != null) {
                AudioDeviceInfo d = audioRecord.getRoutedDevice();
                if (d != null) inputRoute = deviceLabel(d);
            }
        } catch (Exception e) { log("读取输入实际路由失败：" + e); }
        try {
            if (audioTrack != null) {
                AudioDeviceInfo d = audioTrack.getRoutedDevice();
                if (d != null) outputRoute = deviceLabel(d);
            }
        } catch (Exception e) { log("读取输出实际路由失败：" + e); }

        routeText.setText("输入实际：" + inputRoute + "\n"
                + "输出实际：" + outputRoute + "\n"
                + "房间：" + (joined ? "已加入" : "未加入")
                + "，远端用户：" + remoteUsers.size());

        boolean changed = !inputRoute.equals(lastInputRoute) || !outputRoute.equals(lastOutputRoute);
        if (changed) {
            lastInputRoute = inputRoute;
            lastOutputRoute = outputRoute;
            String line = "路由变化（" + reason + "）：输入=" + inputRoute + "；输出=" + outputRoute;
            routeChanges.add(timeNow() + " " + line);
            log(line);
        }
    }

    private void leaveRoom(String reason) {
        publishing = false;
        renderRunning = false;
        joined = false;
        if (trtc != null) {
            try { trtc.stopLocalAudio(); } catch (Exception ignored) {}
            try { trtc.exitRoom(); } catch (Exception ignored) {}
        }
        joinAudioThreads();
        releaseAudio();
        cleanupTrtc();
        joinButton.setEnabled(true);
        leaveButton.setEnabled(false);
        statusText.setText("已离开：" + reason);
        log("离开房间：" + reason);
    }

    private void joinAudioThreads() {
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
            log("onEnterRoom result=" + result);
            if (result > 0) {
                joined = true;
                statusText.setText("已进房，启动自定义音频…");
                try {
                    trtc.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_SPEECH);
                } catch (Exception e) {
                    lastError = e.toString();
                    log("startLocalAudio 失败：" + e);
                }
                try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}
                // TRTC 进房后可能重设音频模式，再声明一次设备偏好。
                try {
                    if (audioRecord != null && selectedInputDevice != null) {
                        audioRecord.setPreferredDevice(selectedInputDevice);
                    }
                    if (audioTrack != null && selectedOutputDevice != null) {
                        audioTrack.setPreferredDevice(selectedOutputDevice);
                    }
                } catch (Exception e) {
                    log("进房后再次设置设备偏好失败：" + e);
                }
                startAudioThreads();
                updateRoutes("entered");
                ui.postDelayed(new Runnable() {
                    @Override public void run() { updateRoutes("entered+1000ms"); }
                }, 1000);
                statusText.setText("通话中（自定义采集/渲染）");
            } else {
                lastError = "onEnterRoom error " + result;
                statusText.setText("进房失败：" + result);
                log("进房失败，result=" + result);
                leaveRoom("进房失败");
            }
        }

        @Override
        public void onExitRoom(int reason) {
            log("onExitRoom reason=" + reason);
            joined = false;
        }

        @Override
        public void onRemoteUserEnterRoom(String uid) {
            log("远端用户进入：" + uid);
            if (!remoteUsers.contains(uid)) remoteUsers.add(uid);
            updateRoutes("remote-enter");
        }

        @Override
        public void onRemoteUserLeaveRoom(String uid, int reason) {
            log("远端用户离开：" + uid + " reason=" + reason);
            remoteUsers.remove(uid);
            updateRoutes("remote-leave");
        }

        @Override
        public void onUserAudioAvailable(String uid, boolean available) {
            log("远端音频可用：" + uid + " = " + available);
            if (available && !remoteUsers.contains(uid)) remoteUsers.add(uid);
            updateRoutes("remote-audio");
        }

        @Override
        public void onError(int errCode, String errMsg, Bundle extraInfo) {
            lastError = "TRTC " + errCode + "：" + errMsg;
            log("TRTC 错误：" + errCode + " " + errMsg);
            statusText.setText("TRTC 错误：" + errCode + " " + errMsg);
        }

        @Override
        public void onWarning(int warningCode, String warningMsg, Bundle extraInfo) {
            log("TRTC 警告：" + warningCode + " " + warningMsg);
        }
    }

    private boolean ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
            statusText.setText("请授予麦克风/蓝牙权限，然后再次点“加入房间”。");
            return false;
        }
        return true;
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;
        boolean all = grantResults.length > 0;
        for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) all = false;
        log(all ? "权限已授予，请再次加入房间。" : "权限被拒绝。");
        statusText.setText(all ? "权限已授予，请再次加入房间。" : "权限被拒绝。");
        if (all) refreshDevices();
    }

    private void copyReport() {
        try {
            String json = buildReport().toString(2);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("trtc-native-report", json));
            Toast.makeText(this, "JSON 已复制", Toast.LENGTH_SHORT).show();
            log("报告已复制。");
        } catch (Exception e) {
            log("复制失败：" + e);
        }
    }

    private void shareReport() {
        try {
            String json = buildReport().toString(2);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "TRTC Native Call Report");
            send.putExtra(Intent.EXTRA_TEXT, json);
            startActivity(Intent.createChooser(send, "分享报告"));
        } catch (Exception e) {
            log("分享失败：" + e);
        }
    }

    private JSONObject buildReport() {
        JSONObject root = new JSONObject();
        try {
            root.put("schema", "android-trtc-native-call/v1");
            root.put("generatedAt", isoNow());
            root.put("manufacturer", Build.MANUFACTURER);
            root.put("model", Build.MODEL);
            root.put("androidVersion", Build.VERSION.RELEASE);
            root.put("sdkInt", Build.VERSION.SDK_INT);
            root.put("sdkAppId", sdkAppId);
            root.put("roomId", roomId);
            root.put("userId", userId);
            root.put("joined", joined);
            root.put("publishing", publishing);
            root.put("remoteUsers", new JSONArray(remoteUsers));
            root.put("selection", new JSONObject()
                    .put("input", selectedInputLabel)
                    .put("output", selectedOutputLabel));
            root.put("actual", new JSONObject()
                    .put("input", lastInputRoute)
                    .put("output", lastOutputRoute));
            root.put("audio", new JSONObject()
                    .put("customCapture", true)
                    .put("customRendering", true)
                    .put("sampleRate", SAMPLE_RATE)
                    .put("frameBytes", FRAME_BYTES)
                    .put("usage", "USAGE_MEDIA"));
            root.put("levels", new JSONObject()
                    .put("local", localLevel)
                    .put("remote", remoteLevel));
            root.put("error", lastError == null ? JSONObject.NULL : lastError);
            root.put("devices", new JSONObject()
                    .put("inputs", deviceArray(inputItems))
                    .put("outputs", deviceArray(outputItems)));
            root.put("routeChanges", new JSONArray(routeChanges));
            root.put("logs", new JSONArray(logs));
        } catch (Exception e) {
            lastError = "buildReport: " + e;
        }
        return root;
    }

    private JSONArray deviceArray(List<DeviceItem> items) {
        JSONArray arr = new JSONArray();
        for (DeviceItem item : items) {
            try {
                AudioDeviceInfo d = item.info;
                JSONObject o = new JSONObject();
                o.put("id", d.getId());
                o.put("type", d.getType());
                o.put("typeName", typeName(d.getType()));
                o.put("productName", String.valueOf(d.getProductName()));
                o.put("isSource", d.isSource());
                o.put("isSink", d.isSink());
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private static class DeviceItem {
        final AudioDeviceInfo info;
        final String label;
        DeviceItem(AudioDeviceInfo info) {
            this.info = info;
            this.label = deviceLabel(info);
        }
        @Override public String toString() { return label; }
    }

    private static String deviceLabel(AudioDeviceInfo d) {
        if (d == null) return "—";
        return typeName(d.getType()) + " | " + d.getProductName() + " | id=" + d.getId();
    }

    private static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "内置听筒";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "内置扬声器";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "内置麦克风";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "蓝牙 A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "蓝牙 SCO/HFP";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有线耳麦";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "有线耳机";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB 设备";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB 耳麦";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "模拟线路";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "数字线路";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "电话";
            case AudioDeviceInfo.TYPE_AUX_LINE: return "AUX";
            default: return "类型 " + type;
        }
    }

    private AudioDeviceInfo selectedInput() {
        int p = inputSpinner.getSelectedItemPosition();
        return p >= 0 && p < inputItems.size() ? inputItems.get(p).info : null;
    }

    private AudioDeviceInfo selectedOutput() {
        int p = outputSpinner.getSelectedItemPosition();
        return p >= 0 && p < outputItems.size() ? outputItems.get(p).info : null;
    }

    private static int findById(List<DeviceItem> items, int id) {
        if (id < 0) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).info.getId() == id) return i;
        }
        return -1;
    }

    private static int findType(List<DeviceItem> items, int type) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).info.getType() == type) return i;
        }
        return -1;
    }

    private void addLabel(LinearLayout root, String text) {
        TextView v = text(text, 12, true);
        v.setTextColor(Color.rgb(55, 70, 90));
        v.setPadding(0, dp(12), 0, dp(4));
        root.addView(v, matchWrap());
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(Color.rgb(24, 34, 48));
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLineSpacing(0, 1.15f);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinHeight(dp(44));
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap(int weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void log(String message) {
        final String line = timeNow() + " " + message;
        logs.add(line);
        while (logs.size() > 400) logs.remove(0);
        ui.post(new Runnable() {
            @Override public void run() {
                logText.append(line + "\n");
                if (logText.getText().length() > 14000) {
                    logText.setText(logText.getText().subSequence(
                            logText.getText().length() - 10000, logText.getText().length()));
                }
            }
        });
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
        int level = (int) Math.min(100, rms / 32768.0 * 100.0 * 3.0);
        return Math.max(0, level);
    }

    private static String timeNow() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date());
    }

    @Override
    protected void onDestroy() {
        leaveRoom("应用销毁");
        super.onDestroy();
    }
}
