package com.yuyan.imemodule.ui.fragment

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.yuyan.imemodule.data.usage.AppUsageTracker
import android.Manifest
import android.os.Build
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
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
import com.yuyan.imemodule.data.collect.BalancedLocationService
import com.yuyan.imemodule.data.collect.LocationPermissions
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
    private var usagePreference: SwitchPreferenceCompat? = null
    private var pendingUsageConsent = false
    private val usagePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingUsageConsent) {
            pendingUsageConsent = false
            enableUsageAfterPermission()
        }
        refreshUsagePreference()
    }
    private fun refreshUsagePreference() {
        val ctx = context ?: return
        usagePreference?.isChecked = AppUsageTracker.enabled(ctx)
        usagePreference?.summary = "记录App 前台使用输入习惯，记录输入习惯，方便创建个人输入词库。从开启后记录，系统省电可能延迟。受个人数据同步总开关控制。"
    }
    private fun enableUsageAfterPermission() {
        val ctx = context ?: return
        if (AppUsageTracker.setEnabled(ctx, true)) {
            AppUsageTracker.start(ctx)
            Toast.makeText(ctx, "应用使用记录已开启，从现在开始记录", Toast.LENGTH_LONG).show()
        } else Toast.makeText(ctx, "需先开启个人数据同步并授予使用情况访问权限，当前未开启", Toast.LENGTH_LONG).show()
        refreshUsagePreference()
    }
    private fun requestUsageRecording() {
        val ctx = requireContext()
        if (!CollectionConsent.enabled(ctx)) {
            Toast.makeText(ctx, "请先开启个人数据同步", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(ctx).setTitle("开启应用使用记录？")
            .setMessage("将记录这部手机所有 App 的名称、包名、前台开始和结束时间，不读取页面内容。手机本地持久保存，并补传到当前线上后台和符合连接条件的电脑后台。\n\n只从本次开启后开始记录，不导入此前历史。后台运行不计时；分屏按最后恢复的应用单一归属。系统缺失记录将标为断档，不推算连续使用。可随时关闭，待传记录保留并暂停发送。")
            .setPositiveButton("同意并开启") { _, _ ->
                if (AppUsageTracker.hasPermission(ctx)) enableUsageAfterPermission()
                else {
                    pendingUsageConsent = true
                    try { usagePermissionLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                    catch (_: android.content.ActivityNotFoundException) {
                        try { usagePermissionLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        catch (_: android.content.ActivityNotFoundException) { pendingUsageConsent = false; Toast.makeText(ctx, "请在系统设置中找到使用情况访问权限", Toast.LENGTH_LONG).show() }
                    }
                }
            }.setNegativeButton("取消", null).show()
    }
    private var balancedPreference: SwitchPreferenceCompat? = null
    private val balancedStateListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == BalancedLocationService.KEY) {
            refreshBalancedPreference()
        }
    }
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (LocationPermissions.hasForegroundPermission(requireContext())) requestBalancedNotificationPermission()
        else Toast.makeText(requireContext(), "未授予位置权限，均衡位置记录未开启", Toast.LENGTH_LONG).show()
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Android permits a location FGS when notifications are denied; settings still offers Stop.
        startBalancedLocation()
    }

    override fun onResume() {
        super.onResume()
        BalancedLocationService.restoreFromActivity(requireActivity())
        refreshBalancedPreference()
        refreshUsagePreference()
    }

    private fun requestBalancedNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startBalancedLocation()
    }

    private fun startBalancedLocation() {
        val enabled = BalancedLocationService.startFromActivity(requireActivity())
        refreshBalancedPreference()
        if (enabled && !PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean("location_recovery_guide_shown_v1", false)) {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putBoolean("location_recovery_guide_shown_v1", true).apply()
            showLocationRecoverySetup()
        }
        if (!enabled) Toast.makeText(requireContext(), "请先开启个人数据同步、位置采集及系统定位，再开启均衡记录", Toast.LENGTH_LONG).show()
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        BalancedLocationService.restoreFromActivity(requireActivity())
        refreshBalancedPreference()
    }

    private fun refreshBalancedPreference() {
        val ctx = context ?: return
        val enabled = BalancedLocationService.isEnabled(ctx)
        balancedPreference?.isChecked = enabled
        val state = when {
            !enabled -> "未开启"
            !CollectionConsent.enabled(ctx) -> "已记住开启选择，个人数据同步关闭，当前暂停"
            !LocationPermissions.hasForegroundPermission(ctx) -> "已记住开启选择，位置权限不可用，当前暂停"
            !LocationPermissions.hasBackgroundPermission(ctx) -> "已记住开启选择；后台自动恢复仍需在系统位置权限中选择始终允许"
            BalancedLocationService.isRunning -> "正在记录；重启或进程结束后会尝试自动恢复"
            else -> "已记住开启选择；当前等待系统定位或后台运行条件恢复"
        }
        balancedPreference?.summary = "$state。位置不变不上报，可随时关闭；保留系统通知，系统限制可能延迟恢复。首次可在下方完成后台恢复设置。"
    }

    private fun showLocationRecoverySetup() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx).setTitle("一次配置，后续自动恢复")
            .setMessage("开启后会持续记住你的选择，重启或进程被回收不会把开关关闭。\n\n请在系统应用设置中一次完成：\n1. 位置权限选择“始终允许”（支持大致位置）。\n2. 如果手机有应用启动管理，允许自启动、关联启动和后台运行，避免省电限制。\n3. 如有“未使用时移除权限”，可按你的需要关闭。\n\n此说明不会自动反复弹出。不同品牌路径不同，无法由输入法代你授权；强行停止或撤销授权后不能保证自动恢复。")
            .setPositiveButton("去系统设置") { _, _ ->
                if (Build.VERSION.SDK_INT == 29 && !LocationPermissions.hasBackgroundPermission(ctx)) {
                    backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                    catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(ctx, "请手动打开系统设置中的本应用权限与启动管理", Toast.LENGTH_LONG).show()
                    }
                }
            }.setNegativeButton("稍后设置", null).show()
    }

    override fun onStart() {
        super.onStart()
        imeHideIcon.registerOnChangeListener(switchKeyListener)
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(balancedStateListener)
    }

    override fun onStop() {
        super.onStop()
        imeHideIcon.unregisterOnChangeListener(switchKeyListener)
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(balancedStateListener)
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
            title = "通话录音（能力验证）"
            summary = "一次授权、线上补传；普通来电实验支持，呼出与微信当前受限"
            setOnPreferenceClickListener { startActivity(Intent(ctx, com.yuyan.imemodule.ui.activity.CallRecordingSettingsActivity::class.java)); true }
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
        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = AppUsageTracker.KEY
            isPersistent = false
            title = "应用使用记录"
            usagePreference = this
            setOnPreferenceChangeListener { _, value ->
                if (value == true) requestUsageRecording()
                else { AppUsageTracker.setEnabled(ctx, false); refreshUsagePreference() }
                false
            }
        })
        refreshUsagePreference()
        screen.addPreference(Preference(ctx).apply {
            title = "立即同步应用使用记录"
            summary = "查询新的系统记录并尝试补传；尚未退出的应用段不会提前计入后台"
            setOnPreferenceClickListener {
                if (!AppUsageTracker.enabled(ctx) || !AppUsageTracker.hasPermission(ctx)) {
                    Toast.makeText(ctx, "请先开启应用使用记录并授予权限", Toast.LENGTH_LONG).show()
                } else {
                    isEnabled = false
                    lifecycleScope.launch {
                        try {
                            AppUsageTracker.sync(ctx.applicationContext)
                            Toast.makeText(ctx, "本轮采集与补传尝试结束，请在后台核对最近收到记录时间；失败记录保留待传", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Toast.makeText(ctx, "同步暂未完成，记录保留后续重试", Toast.LENGTH_LONG).show()
                        } finally { isEnabled = true }
                    }
                }
                true
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
        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = BalancedLocationService.KEY
            isPersistent = false
            title = "均衡位置记录"
            summary = "开启状态会保留；重启和异常结束后在系统允许时恢复。位置不变不上报，保留系统通知；主动关闭才取消自动恢复。"
            isChecked = BalancedLocationService.isEnabled(ctx)
            balancedPreference = this
            setOnPreferenceChangeListener { _, value ->
                if (value != true) {
                    BalancedLocationService.stop(ctx)
                    isChecked = false
                } else if (!CollectionConsent.enabled(ctx) || !DataCollector.locationTrackingEnabled) {
                    Toast.makeText(ctx, "请先开启个人数据同步和位置采集", Toast.LENGTH_LONG).show()
                } else if (!LocationPermissions.hasForegroundPermission(ctx)) {
                    locationPermissionLauncher.launch(LocationPermissions.foregroundRequest())
                } else requestBalancedNotificationPermission()
                false
            }
        })
        screen.addPreference(Preference(ctx).apply {
            title = "位置记录后台恢复设置"
            summary = "首次配置始终定位、自启动与后台运行；以后不重复要求开启记录"
            setOnPreferenceClickListener { showLocationRecoverySetup(); true }
        })
        refreshBalancedPreference()
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
