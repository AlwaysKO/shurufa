package com.yuyan.imemodule.ui.activity

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.MediaPlayer
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import com.yuyan.imemodule.data.callrecording.*
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 所有敏感选择需明确点选；升级不自动勾选新授权，不反复弹权限。 */
class CallRecordingSettingsActivity:AppCompatActivity() {
    private lateinit var automatic:SwitchCompat
    private var updatingSwitch=false
    private var changing=false
    private lateinit var status:TextView
    private lateinit var counts:TextView
    private lateinit var notificationSettings:Button
    private var player:MediaPlayer?=null
    private var previewEpoch=0L
    private var previewJob:Job?=null
    private val main=Handler(Looper.getMainLooper())
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        CallRecordingService.restoreFromActivity(this);refreshStatus()
    }
    private val ticker=object:Runnable{override fun run(){refreshStatus();main.postDelayed(this,1500)}}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);title="通话录音（能力验证）";ServerConfig.init(this)
        enableEdgeToEdge()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,36,32,36)}
        val scroll=ScrollView(this).apply{addView(box)}
        applyCallRecordingWindowInsets(scroll)
        setContentView(scroll)
        fun text(value:String)=TextView(this).apply{text=value;textSize=16f;setPadding(0,12,0,12);box.addView(this)}
        fun button(label:String,action:()->Unit){box.addView(Button(this).apply{text=label;setOnClickListener{action()}},ViewGroup.LayoutParams(-1,-2))}
        automatic=SwitchCompat(this).apply {
            text="自动录音并上传"
            val consent=CallRecordingRuntime.consent(this@CallRecordingSettingsActivity)
            isChecked=consent.wantsRecording||consent.wantsUpload
            box.addView(this)
        }
        text("开启后记住选择；关闭会停止录音和上传，未上传文件保留。首次开启确认一次，系统权限仍需允许。")
        text("普通来电实验支持（含双卡逐卡监听）；呼出、微信暂不支持，两卡并发或呼叫等待会停止录音。双方声音未验证，必要通知和麦克风提示保留。")
        text("唯一上传目标：${ServerConfig.baseUrl}\n线上确认持久保存后才删除本地录音，失败保留。线上接口未部署时无法上传。")
        automatic.setOnCheckedChangeListener{_,checked->
            if(!updatingSwitch&&!changing){
                if(checked)requestEnable() else {
                    CallRecordingRuntime.consent(this).revokeAll()
                    stopService(Intent(this,CallRecordingService::class.java))
                    CallRecordingRuntime.restore(this)
                    stopPreview();refreshStatus()
                }
            }
        }
        status=text("")
        notificationSettings=Button(this).apply{
            text="允许录音通知（打开系统设置）"
            setOnClickListener{
                try{startActivity(CallRecordingService.notificationSettingsIntent(this@CallRecordingSettingsActivity))}
                catch(_:Exception){Toast.makeText(this@CallRecordingSettingsActivity,"请在系统应用设置中允许本应用通知",Toast.LENGTH_LONG).show()}
            }
            box.addView(this);visibility=View.GONE
        }
        counts=text("")
        button("刷新待传与中断状态"){refreshCounts();CallRecordingJobService.wake(this)}
        button("试听最近一条未清理录音"){preview()}
        button("停止试听"){stopPreview()}
        button("返回"){finish()}
    }
    private fun finishChange(){
        changing=false
        automatic.isEnabled=true
        syncSwitch()
        refreshStatus()
    }
    private fun syncSwitch(){
        if(changing||!::automatic.isInitialized)return
        val consent=CallRecordingRuntime.consent(this)
        updatingSwitch=true
        automatic.isChecked=consent.wantsRecording||consent.wantsUpload
        updatingSwitch=false
    }
    private fun requestEnable(){
        if(!CollectionConsent.enabled(this)){
            Toast.makeText(this,"个人数据同步总开关当前关闭，请先开启；这里不会自动开启其他数据采集",Toast.LENGTH_LONG).show()
            finishChange();return
        }
        changing=true;automatic.isEnabled=false
        val target=ServerConfig.baseUrl
        val ticket=CallRecordingRuntime.consent(this).revision
        lifecycleScope.launch {
            try{
                val device=withContext(Dispatchers.IO){DataCollector.deviceId(this@CallRecordingSettingsActivity)}
                if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){finishChange();return@launch}
                if(!CallRecordingRuntime.consent(this@CallRecordingSettingsActivity).needsCombinedConfirmation(device,target)){
                    enable(device,target,ticket);return@launch
                }
                var accepted=false
                AlertDialog.Builder(this@CallRecordingSettingsActivity).setTitle("开启自动录音并上传")
                    .setMessage("仅在已告知并取得通话参与者同意的范围内启用。\n\n同意在支持范围内自动录音，并仅上传至 ${target}；线上确认保存后删除本地录音，失败保留。\n\n当前普通来电实验支持（含双卡），不保证双方声音；保留系统通知，不绕过权限限制。")
                    .setNegativeButton("取消",null)
                    .setPositiveButton("同意并开启"){_,_->accepted=true;enable(device,target,ticket)}
                    .setOnDismissListener{if(!accepted)finishChange()}
                    .show()
            }catch(e:CancellationException){throw e}
            catch(_:Exception){finishChange();Toast.makeText(this@CallRecordingSettingsActivity,"无法读取授权状态，未开启",Toast.LENGTH_LONG).show()}
        }
    }
    private fun enable(device:String,target:String,ticket:Long){
        lifecycleScope.launch {
            try{
                val granted=withContext(Dispatchers.IO){CallRecordingRuntime.consent(this@CallRecordingSettingsActivity).grantCombined(device,target,ticket)}
                if(!granted){finishChange();return@launch}
                CallRecordingRuntime.preferences(this@CallRecordingSettingsActivity).edit().putBoolean("capability_blocked",false).apply()
                CallRecordingRuntime.restore(this@CallRecordingSettingsActivity)
                finishChange()
                if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)&&CallRecordingRuntime.consent(this@CallRecordingSettingsActivity).recordingAllowed(device,target)){
                    val needed=mutableListOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE)
                    if(Build.VERSION.SDK_INT>=33)needed.add(Manifest.permission.POST_NOTIFICATIONS)
                    permission.launch(needed.toTypedArray())
                }
            }catch(e:CancellationException){throw e}
            catch(_:Exception){finishChange();Toast.makeText(this@CallRecordingSettingsActivity,"开启失败，请查看当前状态",Toast.LENGTH_LONG).show()}
        }
    }
    private fun refreshStatus(){
        syncSwitch()
        if(CallRecordingService.activeId!=null)stopPreview()
        if(!::status.isInitialized)return
        val consent=CallRecordingRuntime.consent(this);val p=CallRecordingRuntime.preferences(this)
        val permissionBlock=if(consent.wantsRecording)CallRecordingService.permissionBlock(this) else null
        val state=permissionBlock ?: (p.getString("state","")?:"")
        if(::notificationSettings.isInitialized)notificationSettings.visibility=
            if(permissionBlock in listOf("notifications_required","notification_channel_required"))View.VISIBLE else View.GONE
        val labels=mapOf("waiting_incoming" to "等待普通来电接通（实验）","recording_unverified" to "正在录音，双方声音未验证",
            "system_silenced" to "系统将录音静音，已停止","suspected_silent" to "持续无有效信号，已停止","microphone_unavailable" to "麦克风不可用，已停止",
            "single_sim_required" to "旧单卡限制已更新，等待重新建立监听",
            "no_active_sim" to "未发现活动 SIM，尚未录音",
            "legacy_multi_sim_unsupported" to "Android 6 不支持本实现的逐卡监听",
            "sim_changed" to "SIM 状态改变，已停止；返回此设置页后重建监听",
            "overlapping_calls" to "两卡并发或呼叫等待，已停止录音，等待全部空闲",
            "notifications_required" to "通知权限未允许，尚未启动录音；请点下方按钮允许通知",
            "notification_channel_required" to "录音通知通道被关闭，尚未启动录音；请点下方按钮开启",
            "audio_phone_permissions_required" to "麦克风或电话权限未允许，请关闭再打开开关授权","foreground_restricted" to "系统限制后台麦克风启动",
            "permissions_or_sync_required" to "权限或同步总开关不可用，当前暂停","setup_restricted" to "授权目标/设备或系统条件不满足",
            "phone_state_unavailable" to "无法订阅通话状态","recorder_error" to "录音器异常，已停止","local_finalize_pending" to "文件已保留，等待本地恢复",
            "limit_reached" to "达到单次录音上限，已停止")
        status.text="遇到受限状态可关闭再打开此开关重试。系统回收后需下次进入设置前台尝试恢复。\n录音选择：${if(consent.wantsRecording)"已记住开启"else"关闭"}\n上传授权：${if(consent.wantsUpload)"开启"else"关闭"}\n当前：${if(CallRecordingService.isRunning)labels[state]?:"准备中" else if(consent.wantsRecording)labels[state]?.let{"$it；服务未运行"}?:"未运行，等待前台权限条件" else"未录音"}\n呼出 / 微信：当前不支持自动录音。"
    }
    private fun refreshCounts(){lifecycleScope.launch {
        val value=withContext(Dispatchers.IO){runCatching {
            val box=CallRecordingRuntime.outbox(this@CallRecordingSettingsActivity);val tasks=box.tasks()
            "待传/暂停/失败：${tasks.count{it.uploadStatus!="saved"}}；已保存待清理：${tasks.count{it.uploadStatus=="saved"&&it.cleanupStatus!="deleted"}}\n中断/待封装：${CallRecordingRuntime.sessions(this@CallRecordingSettingsActivity).pendingCount()}；坏清单：${box.invalidEntries().size}。中断文件不自动删除。"
        }.getOrDefault("本地状态读取失败，文件保留")}
        counts.text=value
    }}
    private fun preview(){
        if(CallRecordingService.activeId!=null){Toast.makeText(this,"录音中不播放，避免影响通话",Toast.LENGTH_LONG).show();return}
        stopPreview()
        val epoch=previewEpoch
        previewJob=lifecycleScope.launch {
            val file=withContext(Dispatchers.IO){runCatching{val b=CallRecordingRuntime.outbox(this@CallRecordingSettingsActivity);b.tasks().sortedByDescending{it.metadata.recording_started_at}.map{b.audioFile(it.id)}.firstOrNull{it.isFile}}.getOrNull()}
            if(!previewAllowed(epoch))return@launch
            if(file==null){Toast.makeText(this@CallRecordingSettingsActivity,"暂无可试听的本地录音，已清理的请在后台试听",Toast.LENGTH_LONG).show();return@launch}
            player?.release()
            try{player=MediaPlayer().apply{setDataSource(file.absolutePath);setOnPreparedListener{if(previewAllowed(epoch))it.start() else {it.release();if(player===it)player=null}};setOnErrorListener{_,_,_->Toast.makeText(this@CallRecordingSettingsActivity,"音频无法播放，不能认定录音成功",Toast.LENGTH_LONG).show();true};prepareAsync()}}
            catch(_:Exception){player?.release();player=null;Toast.makeText(this@CallRecordingSettingsActivity,"无法播放该录音",Toast.LENGTH_LONG).show()}
        }
    }
    private fun previewAllowed(epoch:Long)=callPreviewAllowed(epoch,previewEpoch,
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),CallRecordingService.activeId!=null)
    private fun stopPreview(){previewEpoch++;previewJob?.cancel();previewJob=null;player?.release();player=null}
    override fun onResume(){super.onResume();CallRecordingService.restoreFromActivity(this);main.post(ticker);refreshCounts()}
    override fun onPause(){main.removeCallbacks(ticker);stopPreview();super.onPause()}
}


// 将滚动视口整体放在系统栏/刘海安全区域，避免顶部开关及底部按钮被遮挡。
internal fun applyCallRecordingWindowInsets(root:View) {
    ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->
        val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
        insets
    }
    root.doOnAttach{ViewCompat.requestApplyInsets(it)}
}
