package com.example.audioroutetest;

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
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Minimal Android audio-route test:
 * AudioRecord (built-in mic) -> AudioTrack (preferred output / Bluetooth A2DP).
 *
 * This is deliberately dependency-free (no AndroidX) so it can be built with
 * a plain Android Studio project.
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 42;
    private static final int SAMPLE_RATE = 48000;

    private AudioManager audioManager;
    private Spinner modeSpinner;
    private Spinner sourceSpinner;
    private Spinner inputSpinner;
    private Spinner outputSpinner;
    private CheckBox communicationUsage;
    private TextView statusText;
    private TextView routeText;
    private TextView logText;
    private Button startButton;
    private Button stopButton;
    private Button refreshButton;
    private Button copyButton;
    private Button shareButton;

    private final List<DeviceItem> inputItems = new ArrayList<>();
    private final List<DeviceItem> outputItems = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final List<String> routeChanges = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread worker;
    private volatile boolean running = false;
    private boolean micMode = true;
    private boolean lastMicMode = true;
    private int bufferBytes = 0;
    private String lastError = null;
    private String lastInputRoute = "—";
    private String lastOutputRoute = "—";
    private String selectedInputLabel = "—";
    private String selectedOutputLabel = "—";

    private AudioRecord.OnRoutingChangedListener recordRouteListener;
    private AudioTrack.OnRoutingChangedListener trackRouteListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        buildUi();
        refreshDevices();
        log("应用启动；请先连接蓝牙，再选择输入/输出并开始测试。");
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

        TextView title = text("Audio Route Test", 24, true);
        root.addView(title);

        TextView subtitle = text(
                "原生 AudioRecord -> AudioTrack 测试：选择内置麦克风和蓝牙 A2DP 输出，观察系统实际路由。",
                13, false);
        subtitle.setTextColor(Color.rgb(80, 92, 110));
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        TextView warning = text(
                "注意：启动回环后会从所选输出播放麦克风声音。请先戴耳机、降低音量，避免啸叫。",
                12, false);
        warning.setTextColor(Color.rgb(150, 85, 0));
        warning.setBackgroundColor(Color.rgb(255, 244, 215));
        warning.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(warning, matchWrap());

        addLabel(root, "测试模式");
        modeSpinner = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"麦克风回环（内置麦克风 -> 输出）", "440Hz 测试音（只测输出）"});
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);
        root.addView(modeSpinner, matchWrap());

        addLabel(root, "输入麦克风");
        inputSpinner = new Spinner(this);
        root.addView(inputSpinner, matchWrap());

        addLabel(root, "输入音源");
        sourceSpinner = new Spinner(this);
        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"MIC（原始麦克风，推荐）", "VOICE_RECOGNITION（语音识别）"});
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpinner.setAdapter(sourceAdapter);
        root.addView(sourceSpinner, matchWrap());

        addLabel(root, "输出设备");
        outputSpinner = new Spinner(this);
        root.addView(outputSpinner, matchWrap());

        communicationUsage = new CheckBox(this);
        communicationUsage.setText("使用 USAGE_VOICE_COMMUNICATION（默认关闭，使用 USAGE_MEDIA）");
        communicationUsage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(communicationUsage, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        root.addView(row, matchWrap());

        refreshButton = button("刷新设备");
        row.addView(refreshButton, weightWrap(1));
        startButton = button("开始测试");
        row.addView(startButton, weightWrap(1));
        stopButton = button("停止");
        stopButton.setEnabled(false);
        row.addView(stopButton, weightWrap(1));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        root.addView(row2, matchWrap());
        copyButton = button("复制 JSON");
        row2.addView(copyButton, weightWrap(1));
        shareButton = button("分享 JSON");
        row2.addView(shareButton, weightWrap(1));

        addLabel(root, "状态");
        statusText = text("就绪", 13, false);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setBackgroundColor(Color.rgb(244, 248, 253));
        root.addView(statusText, matchWrap());

        addLabel(root, "实际路由");
        routeText = text("尚未启动。", 13, false);
        routeText.setPadding(dp(10), dp(8), dp(10), dp(8));
        routeText.setBackgroundColor(Color.rgb(244, 248, 253));
        root.addView(routeText, matchWrap());

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
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startTest(); }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopTest("手动停止"); }
        });
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyReport(); }
        });
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareReport(); }
        });
    }

    private void refreshDevices() {
        try {
            int oldIn = -1;
            int oldOut = -1;
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
            if (outPos < 0) outPos = findType(outputItems, AudioDeviceInfo.TYPE_WIRED_HEADSET);
            if (outPos < 0) outPos = findType(outputItems, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            if (outPos < 0 && !outputItems.isEmpty()) outPos = 0;
            if (outPos >= 0) outputSpinner.setSelection(outPos);

            statusText.setText("设备已刷新："
                    + inputItems.size() + " 个输入 / "
                    + outputItems.size() + " 个输出");
            log("设备刷新：输入 " + inputItems.size() + "，输出 " + outputItems.size());
            if (outputItems.isEmpty()) {
                log("没有可用输出。请先连接蓝牙或在系统设置中检查。");
            } else {
                log("提示：输出优先选择 Bluetooth A2DP；输入优先选择 Built-in Mic。");
            }
        } catch (Exception e) {
            lastError = e.toString();
            log("刷新设备失败：" + e);
            statusText.setText("刷新设备失败：" + e.getMessage());
        }
    }

    private void startTest() {
        if (running) stopTest("重新开始");
        lastError = null;
        routeChanges.clear();
        micMode = modeSpinner.getSelectedItemPosition() == 0;
        lastMicMode = micMode;

        if (!ensurePermissions(micMode)) {
            return;
        }

        AudioDeviceInfo input = selectedInput();
        AudioDeviceInfo output = selectedOutput();
        selectedInputLabel = input == null ? "—" : deviceLabel(input);
        selectedOutputLabel = output == null ? "—" : deviceLabel(output);

        if (output == null) {
            statusText.setText("没有选择输出设备。请点“刷新设备”，并确认蓝牙已连接。");
            refreshDevices();
            return;
        }
        if (micMode && input == null) {
            statusText.setText("麦克风回环模式需要选择输入设备。");
            return;
        }

        try {
            try {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                log("AudioManager.MODE_NORMAL 已设置（避免上次通信模式残留）");
            } catch (Exception e) {
                log("设置 MODE_NORMAL 失败：" + e);
            }

            int recMin = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int trkMin = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            bufferBytes = Math.max(Math.max(recMin, trkMin), 4096) * 2;

            if (micMode) {
                int source = sourceSpinner.getSelectedItemPosition() == 1
                        ? MediaRecorder.AudioSource.VOICE_RECOGNITION
                        : MediaRecorder.AudioSource.MIC;
                audioRecord = new AudioRecord(
                        source, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord 初始化失败，state="
                            + audioRecord.getState());
                }
                boolean inputPreferred = audioRecord.setPreferredDevice(input);
                log("AudioRecord 选择输入：" + deviceLabel(input)
                        + "，setPreferredDevice=" + inputPreferred);
            }

            int usage = communicationUsage.isChecked()
                    ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                    : AudioAttributes.USAGE_MEDIA;
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat trackFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(trackFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack 初始化失败，state="
                        + audioTrack.getState());
            }
            boolean outputPreferred = audioTrack.setPreferredDevice(output);
            audioTrack.setVolume(0.65f);
            log("AudioTrack 选择输出：" + deviceLabel(output)
                    + "，setPreferredDevice=" + outputPreferred
                    + "，usage=" + (usage == AudioAttributes.USAGE_MEDIA ? "USAGE_MEDIA" : "USAGE_VOICE_COMMUNICATION"));

            if (audioRecord != null) audioRecord.startRecording();
            audioTrack.play();
            running = true;
            startWorker();
            installRoutingListeners();

            statusText.setText(micMode ? "麦克风回环运行中" : "440Hz 测试音播放中");
            routeText.setText("正在读取实际路由…");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            log("开始测试：模式=" + (micMode ? "麦克风回环" : "440Hz 测试音")
                    + "，输入=" + selectedInputLabel
                    + "，输出=" + selectedOutputLabel);

            updateRoutes("start");
            ui.postDelayed(new Runnable() {
                @Override public void run() { updateRoutes("start+700ms"); }
            }, 700);
        } catch (Exception e) {
            lastError = e.toString();
            log("启动失败：" + e);
            statusText.setText("启动失败：" + e.getMessage());
            stopTest("启动失败");
        }
    }

    private void startWorker() {
        worker = new Thread(new Runnable() {
            @Override public void run() {
                if (micMode) runMicLoopback(); else runTone();
            }
        }, "audio-route-test-worker");
        worker.start();
    }

    private void runMicLoopback() {
        short[] buffer = new short[1024];
        while (running && audioRecord != null && audioTrack != null) {
            int read;
            try {
                read = audioRecord.read(buffer, 0, buffer.length);
            } catch (Exception e) {
                log("AudioRecord.read 异常：" + e);
                break;
            }
            if (read > 0) {
                int written = audioTrack.write(buffer, 0, read);
                if (written < 0) {
                    log("AudioTrack.write 失败：" + written);
                    break;
                }
            } else if (read < 0) {
                log("AudioRecord.read 失败：" + read);
                break;
            }
        }
    }

    private void runTone() {
        short[] buffer = new short[1024];
        double phase = 0.0;
        double step = 2.0 * Math.PI * 440.0 / SAMPLE_RATE;
        while (running && audioTrack != null) {
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = (short) (Math.sin(phase) * 0.18 * Short.MAX_VALUE);
                phase += step;
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
            }
            int written = audioTrack.write(buffer, 0, buffer.length);
            if (written < 0) {
                log("AudioTrack.write 失败：" + written);
                break;
            }
        }
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
        } catch (Exception e) {
            log("读取输入实际路由失败：" + e);
        }
        try {
            if (audioTrack != null) {
                AudioDeviceInfo d = audioTrack.getRoutedDevice();
                if (d != null) outputRoute = deviceLabel(d);
            }
        } catch (Exception e) {
            log("读取输出实际路由失败：" + e);
        }

        routeText.setText("输入实际：" + inputRoute + "\n"
                + "输出实际：" + outputRoute + "\n"
                + "运行状态：" + (running ? "运行中" : "已停止") + "\n"
                + "触发：" + reason);

        boolean changed = !inputRoute.equals(lastInputRoute) || !outputRoute.equals(lastOutputRoute);
        if (changed) {
            lastInputRoute = inputRoute;
            lastOutputRoute = outputRoute;
            String line = "路由变化（" + reason + "）：输入=" + inputRoute + "；输出=" + outputRoute;
            routeChanges.add(timeNow() + " " + line);
            log(line);
        }
    }

    private void stopTest(String reason) {
        running = false;
        removeRoutingListeners();
        if (worker != null) {
            try { worker.join(800); } catch (InterruptedException ignored) {}
            worker = null;
        }
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
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        statusText.setText("已停止：" + reason);
        routeText.setText("已停止。\n上次输入：" + lastInputRoute + "\n上次输出：" + lastOutputRoute);
        log("停止测试：" + reason);
    }

    private void copyReport() {
        try {
            String json = buildReport().toString(2);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("audio-route-report", json));
            Toast.makeText(this, "JSON 已复制", Toast.LENGTH_SHORT).show();
            log("JSON 报告已复制到剪贴板。");
        } catch (Exception e) {
            log("复制失败：" + e);
        }
    }

    private void shareReport() {
        try {
            String json = buildReport().toString(2);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Audio Route Test Report");
            send.putExtra(Intent.EXTRA_TEXT, json);
            startActivity(Intent.createChooser(send, "分享测试报告"));
        } catch (Exception e) {
            log("分享失败：" + e);
        }
    }

    private JSONObject buildReport() {
        JSONObject root = new JSONObject();
        try {
            root.put("schema", "android-audio-route-test/v1");
            root.put("generatedAt", isoNow());
            root.put("manufacturer", Build.MANUFACTURER);
            root.put("model", Build.MODEL);
            root.put("androidVersion", Build.VERSION.RELEASE);
            root.put("sdkInt", Build.VERSION.SDK_INT);
            root.put("mode", micMode ? "mic-loopback" : "tone-440");
            root.put("source", sourceSpinner.getSelectedItemPosition() == 1 ? "VOICE_RECOGNITION" : "MIC");
            root.put("usage", communicationUsage.isChecked() ? "USAGE_VOICE_COMMUNICATION" : "USAGE_MEDIA");
            root.put("selection", new JSONObject()
                    .put("input", selectedInputLabel)
                    .put("output", selectedOutputLabel));
            root.put("actual", new JSONObject()
                    .put("input", lastInputRoute)
                    .put("output", lastOutputRoute));
            root.put("running", running);
            root.put("sampleRate", SAMPLE_RATE);
            root.put("bufferBytes", bufferBytes);
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
        String address = "";
        try {
            String a = d.getAddress();
            if (a != null && !a.isEmpty()) address = " " + a;
        } catch (Exception ignored) {}
        return typeName(d.getType()) + " | " + d.getProductName() + address + " | id=" + d.getId();
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

    private boolean ensurePermissions(boolean needMic) {
        List<String> missing = new ArrayList<>();
        if (needMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 31
                && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
            statusText.setText("请授予权限，然后再次点“开始测试”。");
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
        log(all ? "权限已授予，请再次点“开始测试”。" : "权限被拒绝，无法进行对应测试。");
        statusText.setText(all ? "权限已授予，请再次点“开始测试”。" : "权限被拒绝。");
        if (all) refreshDevices();
    }

    @Override
    protected void onDestroy() {
        stopTest("页面销毁");
        super.onDestroy();
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
        while (logs.size() > 300) logs.remove(0);
        ui.post(new Runnable() {
            @Override public void run() {
                logText.append(line + "\n");
                if (logText.getText().length() > 12000) {
                    logText.setText(logText.getText().subSequence(
                            logText.getText().length() - 9000, logText.getText().length()));
                }
                View parent = (View) logText.getParent();
                if (parent != null) parent.requestLayout();
            }
        });
    }

    private static String timeNow() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date());
    }
}
