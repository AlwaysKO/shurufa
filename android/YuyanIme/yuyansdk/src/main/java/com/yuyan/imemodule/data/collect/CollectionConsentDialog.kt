package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.appcompat.app.AlertDialog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object CollectionConsentDialog {
    // 同一个设置页可能多次恢复；复用仍在显示的弹窗，避免确认后底下还留着一层。
    private val dialogs = WeakHashMap<Context, WeakReference<AlertDialog>>()

    fun show(context: Context, onEnabled: () -> Unit = {}) {
        if (dialogs[context]?.get()?.isShowing == true) return
        val dialog = AlertDialog.Builder(context)
            .setTitle("开启个人数据同步？")
            .setMessage("开启后，将记录普通输入、选词使用情况，以及授予位置权限后的定位。已另行开启的剪贴板或聊天采集也受此总开关控制。\n\n数据会存储在手机本地，联网后分批上传至 https://my.dog8ball.com，并补传至你配置的电脑 API（默认 USB 连接的 127.0.0.1:3000）。\n\n个人词条（含可读取的系统公开词典）与选词学习权重会备份到主后台，供你在后台查看来源、绑定手机、管理和恢复；不会读取其他输入法的私有数据。\n\n密码框、无痕字段及可识别的验证码等敏感信息不采集。未标识的敏感内容无法保证自动识别，请先关闭同步再输入。\n\n可随时在「设置 → 其他 → 个人数据同步」关闭。关闭暂停发送，待传记录仍保留在手机，重新开启后继续补传。不影响离线打字与本地词典。")
            .setPositiveButton("同意并开启") { _, _ -> DataCollector.setCollectionEnabled(context, true); onEnabled() }
            .setNegativeButton("暂不开启") { _, _ -> DataCollector.setCollectionEnabled(context, false) }
            .setCancelable(false)
            .create()
        dialogs[context] = WeakReference(dialog)
        dialog.setOnDismissListener {
            if (dialogs[context]?.get() === dialog) dialogs.remove(context)
        }
        dialog.show()
    }
}
