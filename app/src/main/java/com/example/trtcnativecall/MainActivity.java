package com.example.trtcnativecall;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal UI: select input microphone and output device, then start/stop the
 * foreground call service. Credentials are fixed in CallService and are not shown.
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 44;

    private AudioManager audioManager;
    private Spinner inputSpinner;
    private Spinner outputSpinner;
    private TextView statusText;
    private TextView routeText;
    private TextView localLevelText;
    private TextView remoteLevelText;
    private Button startButton;
    private Button stopButton;

    private final List<DeviceItem> inputItems = new ArrayList<>();
    private final List<DeviceItem> outputItems = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private CallService service;
    private boolean bound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CallService.LocalBinder) binder).getService();
            bound = true;
            refreshServiceUi();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            refreshServiceUi();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        buildUi();
        refreshDevices();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = text("TRTC 原生通话", 26, true);
        root.addView(title);

        TextView sub = text("默认配置已隐藏。选择设备后开始通话。", 13, false);
        sub.setTextColor(Color.rgb(80, 92, 110));
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        addLabel(root, "输入麦克风");
        inputSpinner = new Spinner(this);
        root.addView(inputSpinner, matchWrap());

        addLabel(root, "输出设备");
        outputSpinner = new Spinner(this);
        root.addView(outputSpinner, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, 0);
        root.addView(row, matchWrap());
        startButton = button("开始通话");
        row.addView(startButton, weightWrap(1));
        stopButton = button("结束通话");
        stopButton.setEnabled(false);
        row.addView(stopButton, weightWrap(1));

        TextView note = text(
                "开始后会在通知栏显示常驻通知；退到后台或锁屏仍会保持通话。",
                12, false);
        note.setTextColor(Color.rgb(90, 105, 125));
        note.setPadding(0, dp(10), 0, 0);
        root.addView(note, matchWrap());

        addLabel(root, "状态");
        statusText = text("未通话", 14, true);
        statusText.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusText.setBackgroundColor(Color.rgb(244, 248, 253));
        root.addView(statusText, matchWrap());

        addLabel(root, "实际路由");
        routeText = text("尚未开始。", 13, false);
        routeText.setPadding(dp(12), dp(10), dp(12), dp(10));
        routeText.setBackgroundColor(Color.rgb(244, 248, 253));
        root.addView(routeText, matchWrap());

        LinearLayout levels = new LinearLayout(this);
        levels.setOrientation(LinearLayout.HORIZONTAL);
        levels.setPadding(0, dp(10), 0, 0);
        root.addView(levels, matchWrap());
        localLevelText = text("本地 0", 12, false);
        localLevelText.setPadding(dp(10), dp(8), dp(10), dp(8));
        localLevelText.setBackgroundColor(Color.rgb(244, 248, 253));
        levels.addView(localLevelText, weightWrap(1));
        remoteLevelText = text("远端 0", 12, false);
        remoteLevelText.setPadding(dp(10), dp(8), dp(10), dp(8));
        remoteLevelText.setBackgroundColor(Color.rgb(244, 248, 253));
        levels.addView(remoteLevelText, weightWrap(1));

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startCall(); }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopCall(); }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, CallService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        ui.post(poller);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(poller);
        if (bound) {
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
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
        } catch (Exception e) {
            statusText.setText("读取设备失败：" + e.getMessage());
        }
    }

    private void startCall() {
        if (!ensurePermissions()) return;
        AudioDeviceInfo input = selectedInput();
        AudioDeviceInfo output = selectedOutput();
        if (input == null || output == null) {
            statusText.setText("没有可用的输入或输出设备。请连接蓝牙后重试。");
            refreshDevices();
            return;
        }

        Intent intent = new Intent(this, CallService.class)
                .setAction(CallService.ACTION_START)
                .putExtra(CallService.EXTRA_INPUT_ID, input.getId())
                .putExtra(CallService.EXTRA_OUTPUT_ID, output.getId());
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusText.setText("正在启动通话…");
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        inputSpinner.setEnabled(false);
        outputSpinner.setEnabled(false);
    }

    private void stopCall() {
        Intent intent = new Intent(this, CallService.class).setAction(CallService.ACTION_STOP);
        startService(intent);
        statusText.setText("正在结束通话…");
        stopButton.setEnabled(false);
    }

    private void refreshServiceUi() {
        if (service == null) {
            statusText.setText("未连接通话服务");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            inputSpinner.setEnabled(true);
            outputSpinner.setEnabled(true);
            return;
        }
        boolean running = service.isRunning();
        String s = service.getStatus();
        if (!running && service.getLastError() != null) {
            s += "\n" + service.getLastError();
        }
        statusText.setText(s);
        routeText.setText("输入实际：" + service.getInputRoute()
                + "\n输出实际：" + service.getOutputRoute()
                + "\n播放欠载：" + service.getUnderrunCount());
        localLevelText.setText("本地 " + service.getLocalLevel());
        remoteLevelText.setText("远端 " + service.getRemoteLevel());

        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        inputSpinner.setEnabled(!running);
        outputSpinner.setEnabled(!running);
    }

    private boolean ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 31
                && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
            statusText.setText("请授予麦克风、蓝牙和通知权限，然后再次点“开始通话”。");
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
        statusText.setText(all ? "权限已授予，请再次点“开始通话”。" : "权限被拒绝。");
        if (all) refreshDevices();
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

    private static class DeviceItem {
        final AudioDeviceInfo info;
        final String label;
        DeviceItem(AudioDeviceInfo info) {
            this.info = info;
            this.label = label(info);
        }
        @Override public String toString() { return label; }
    }

    private static String label(AudioDeviceInfo d) {
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: type = "内置麦克风"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: type = "蓝牙 A2DP"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: type = "蓝牙 SCO"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: type = "内置扬声器"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: type = "内置听筒"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: type = "有线耳麦"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: type = "有线耳机"; break;
            case AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB 耳麦"; break;
            default: type = "类型 " + d.getType();
        }
        return type + " | " + d.getProductName() + " | id=" + d.getId();
    }

    private void addLabel(LinearLayout root, String text) {
        TextView v = text(text, 12, true);
        v.setTextColor(Color.rgb(55, 70, 90));
        v.setPadding(0, dp(16), 0, dp(6));
        root.addView(v, matchWrap());
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(Color.rgb(24, 34, 48));
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setMinHeight(dp(48));
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
}
