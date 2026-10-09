package com.yuyan.redpacket.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

public final class ProbeActivity extends Activity {
    private TextView status;
    private volatile IProbeService service;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ProbeConnection connectionState = new ProbeConnection(this::bindDiagnostic,
        () -> call("status"), () -> show("Shizuku 未启动或尚未授权，请先完成第1步。"));
    private final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
        new ComponentName("com.yuyan.redpacket.probe", ProbeService.class.getName()))
        .daemon(true).processNameSuffix("display_probe").debuggable(false).version(5);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (isDestroyed()) return;
            service = IProbeService.Stub.asInterface(binder);
            show("诊断服务已连接。点击启动后只打开系统设置，五分钟自动结束。");
            connectionState.connected();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null; connectionState.disconnected();
            show("诊断服务已断开，可能已到五分钟截止；未确认原因。点刷新可重新连接查询。");
        }
    };
    private final Shizuku.OnBinderReceivedListener received = () -> {
        show("Shizuku 已启动，请授权并连接诊断服务。");
        connectionState.resume(canConnect());
    };
    private final Shizuku.OnBinderDeadListener dead = () -> {
        call("stop");
        service = null; connectionState.disconnected();
        show("Shizuku 已断开；已请求停止，若无法送达则等待五分钟上限清理。");
    };
    private final Shizuku.OnRequestPermissionResultListener permission = (code, grant) -> {
        if (code == 42) show(grant == PackageManager.PERMISSION_GRANTED ? "授权成功，可以连接。" : "未获授权。");
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density); layout.setPadding(pad, pad, pad, pad);
        TextView explanation = new TextView(this);
        explanation.setText("静默副屏诊断\n\n需 Shizuku 13+ 以无线调试启动。此诊断只打开系统设置，不打开微信、不领取红包、不保存画面。运行最长五分钟。\n\n启动后可回桌面操作其他应用；手动刷新查看状态。帧数增长只证明有帧，不证明画面正确或主屏触摸正常。");
        layout.addView(explanation);
        add(layout, "1. 请求 Shizuku 授权", this::requestPermission);
        add(layout, "2. 连接诊断服务", this::connect);
        add(layout, "3. 启动五分钟副屏实验", () -> call("start"));
        add(layout, "停止并释放副屏", () -> call("stop"));
        add(layout, "刷新状态", () -> connectionState.refresh(canConnect()));
        status = new TextView(this); layout.addView(status); setContentView(layout);
        Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
        if (!Shizuku.pingBinder()) show("未检测到已启动的 Shizuku。请先在 Shizuku 中完成无线调试配对和启动。");
    }
    private void add(LinearLayout layout, String title, Runnable action) {
        Button button = new Button(this); button.setText(title);
        button.setOnClickListener(view -> action.run()); layout.addView(button);
    }
    private void requestPermission() {
        try {
            if (!Shizuku.pingBinder()) { show("请先启动 Shizuku。"); return; }
            if (Shizuku.getUid() != 2000) { show("仅接受无线调试/shell 模式，请勿使用 Root 模式。"); return; }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) show("已获授权，可以连接。");
            else Shizuku.requestPermission(42);
        } catch (RuntimeException e) { show("无法请求授权，请检查 Shizuku 状态。"); }
    }
    private void connect() {
        if (canConnect()) connectionState.resume(true);
        else show("请先以 shell 模式启动 Shizuku 并授权。");
    }
    private boolean canConnect() {
        try {
            return Shizuku.pingBinder() && Shizuku.getUid() == 2000
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) { return false; }
    }
    private void bindDiagnostic() {
        try { Shizuku.bindUserService(args, connection); show("正在连接……"); }
        catch (RuntimeException e) { connectionState.disconnected(); show("连接失败。"); }
    }
    private void call(String action) {
        IProbeService current = service;
        if (current == null) { show("请先连接诊断服务。"); return; }
        if (io.isShutdown()) return;
        show("正在执行……");
        io.execute(() -> {
            try {
                String result = action.equals("start") ? current.start() : action.equals("stop") ? current.stop() : current.status();
                JSONObject report = new JSONObject(result);
                if (report.optJSONObject("failure") != null) {
                    getSharedPreferences("diagnostic", MODE_PRIVATE).edit()
                        .putString("last_failure", report.toString()).apply();
                }
                show(result);
            } catch (Exception e) { show("服务调用失败；未确认实验状态，请等待限时清理或重新连接停止。"); }
        });
    }
    private void show(String message) {
        runOnUiThread(() -> {
            if (isDestroyed() || status == null) return;
            String last = getSharedPreferences("diagnostic", MODE_PRIVATE).getString("last_failure", "");
            String current = message.contains("\"state\":\"MAIN_SETTINGS_IN_USE\"")
                ? message + "\n\n主屏正在使用系统设置，已停止实验并释放副屏。返回本页后可手动重新启动。" : message;
            status.setText(!last.isEmpty() && !message.equals(last)
                ? current + "\n\n上次失败记录（历史状态）：\n" + last : current);
        });
    }
    @Override public void onResume() {
        super.onResume(); connectionState.resume(canConnect());
    }
    @Override public void onDestroy() {
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        if (connectionState.attached() && Shizuku.pingBinder()) {
            try { Shizuku.unbindUserService(args, connection, false); } catch (RuntimeException ignored) { }
        }
        io.shutdown(); super.onDestroy();
    }
}
