package com.yuyan.imemodule.ui.fragment

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.CollectionConsentDialog
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.capture.adapter.DouyinCaptureDiagnostics
import com.yuyan.imemodule.manager.UserDataManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.ui.activity.LauncherActivity
import com.yuyan.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.addPreference
import com.yuyan.imemodule.utils.importErrorDialog
import com.yuyan.imemodule.utils.queryFileName
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.imemodule.view.widget.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable

private val imeHideIcon = AppPrefs.getInstance().other.imeHideIcon

private val switchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
    val componentName = ComponentName(Launcher.instance.context.packageName, LauncherActivity::class.java.name)
    Launcher.instance.context.packageManager.setComponentEnabledSetting(componentName, if(value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
}

class OtherSettingsFragment: ManagedPreferenceFragment(AppPrefs.getInstance().other){

    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<String>

    override fun onStart() {
        super.onStart()
        imeHideIcon.registerOnChangeListener(switchKeyListener)
    }

    override fun onStop() {
        super.onStop()
        imeHideIcon.unregisterOnChangeListener(switchKeyListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val name = cr.queryFileName(uri) ?: return@withContext
                        if (!name.endsWith(".zip")) {
                            ctx.importErrorDialog(R.string.exception_user_data_filename, name)
                            return@withContext
                        }
                        try {
                            val inputStream = cr.openInputStream(uri)!!
                            UserDataManager.import(inputStream).getOrThrow()
                            lifecycleScope.launch(NonCancellable + Dispatchers.Main) {
                                delay(400L)
                                AppUtil.exit()
                            }
                            withContext(Dispatchers.Main) {
                                AppUtil.showRestartNotification(ctx)
                                Toast.makeText(ctx, R.string.user_data_imported, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            UserDataManager.export(outputStream).getOrThrow()
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        screen.addPreference(Preference(ctx).apply {
            title = "抖音识别诊断"
            summary = "在手机上查看最近一次页面识别状态；不含聊天内容"
            setOnPreferenceClickListener {
                val last = DouyinCaptureDiagnostics(ctx).read()
                val status = last?.let {
                    "最近状态：${it.status.label}\n记录时间：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.observedAt))}"
                } ?: "尚无识别记录。请确认个人数据同步和系统无障碍服务已开启，再进入抖音私信聊天页。"
                val consent = if (CollectionConsent.enabled(ctx)) "开启" else "关闭"
                AlertDialog.Builder(ctx).setTitle("抖音识别诊断")
                    .setMessage("个人数据同步：$consent\n$status\n\n此状态仅说明页面识别，不代表截图或上传成功。最近记录可能来自已退出的页面；不保存标题、正文或截图。")
                    .setPositiveButton(android.R.string.ok, null).show()
                true
            }
        })
        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = CollectionConsent.KEY
            setDefaultValue(false)
            title = "个人数据同步"
            summary = "普通输入与使用统计双端同步；关闭即暂停采集和补传，待传记录仍保留。位置另受权限及下方开关控制。"
            setOnPreferenceChangeListener { _, value ->
                if (value == true) CollectionConsentDialog.show(ctx) { isChecked = true }
                else { DataCollector.setCollectionEnabled(ctx, false); isChecked = false }
                false
            }
        })
        // 数据采集：服务器地址（key 与 DataCollector/ServerConfig 约定一致）
        screen.addPreference(EditTextPreference(ctx).apply {
            key = "server_url"
            setTitle(R.string.setting_server_url)
            setSummaryProvider { pref ->
                val v = (pref as EditTextPreference).text
                if (v.isNullOrBlank()) ctx.getString(R.string.setting_server_url_auto) else v
            }
            dialogTitle = ctx.getString(R.string.setting_server_url)
            setDialogMessage(R.string.setting_server_url_tips)
        })
        // 首次同意总开关后位置仍受单独开关与系统权限控制
        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = "location_tracking_enable"
            setDefaultValue(true)
            setTitle(R.string.setting_location_tracking)
            setSummary(R.string.setting_location_tracking_tips)
            setOnPreferenceChangeListener { _, newValue ->
                DataCollector.setLocationTrackingEnabled(ctx, newValue as Boolean)
                true
            }
        })
        screen.addPreference(R.string.export_user_data) {
            lifecycleScope.launch {
                exportTimestamp = System.currentTimeMillis()
                exportLauncher.launch("yuyanIme_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
            }
        }
        screen.addPreference(R.string.import_user_data) {
            AlertDialog.Builder(ctx)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.import_user_data)
                .setMessage(R.string.confirm_import_user_data)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    importLauncher.launch("application/zip")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
