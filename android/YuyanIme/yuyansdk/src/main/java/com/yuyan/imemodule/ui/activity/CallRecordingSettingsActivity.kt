package com.yuyan.imemodule.ui.activity

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
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
import com.yuyan.imemodule.data.calllog.*
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
    private lateinit var systemStatus:TextView
    private var audioReadable=false
    private var readableSources=emptySet<String>()
    private var sourceAccessLoaded=false
    private var sourceAccessJob:Job?=null
    private lateinit var callLogSwitch:SwitchCompat
    private lateinit var callLogStatus:TextView
    private var updatingCallLog=false
    private var changingCallLog=false
    private val callLogPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){
        PhoneCallLogJobService.wake(this);refreshStatus()
    }
    private val audioPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){
        CallRecordingJobService.wake(this);refreshSourceAccess()
    }
    private var player:MediaPlayer?=null
    private var previewEpoch=0L
    private var previewJob:Job?=null
    private val main=Handler(Looper.getMainLooper())
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        CallRecordingService.restoreFromActivity(this);CallRecordingJobService.wake(this);refreshSourceAccess()
    }
    private val phoneDirectory=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){saveDirectory("phone",it.resultCode,it.data)}
    private val wechatDirectory=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){saveDirectory("wechat",it.resultCode,it.data)}
    private val ticker=object:Runnable{override fun run(){refreshStatus();main.postDelayed(this,1500)}}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);title="音频与文件";ServerConfig.init(this)
        enableEdgeToEdge()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,36,32,36)}
        val scroll=ScrollView(this).apply{addView(box)}
        applyCallRecordingWindowInsets(scroll)
        setContentView(scroll)
        fun text(value:String)=TextView(this).apply{text=value;textSize=16f;setPadding(0,12,0,12);box.addView(this)}
        fun button(label:String,action:()->Unit){box.addView(Button(this).apply{text=label;setOnClickListener{action()}},ViewGroup.LayoutParams(-1,-2))}
        automatic=SwitchCompat(this).apply {
            text="音频与文件"
            val consent=CallRecordingRuntime.consent(this@CallRecordingSettingsActivity)
            isChecked=consent.wantsRecording||consent.wantsUpload
            box.addView(this)
        }
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
        counts=text("")
        button("确认或扩展授权"){requestEnable()}
        button("通知读取授权"){
            try{startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}
            catch(_:Exception){Toast.makeText(this,"无法打开通知读取设置，请到系统特殊权限中授权",Toast.LENGTH_LONG).show()}
        }
        button("获取当前文件"){requestManualWechatRecording()}
        systemStatus=text("")
        button("授权并立即查找"){
            if(!CallRecordingRuntime.consent(this).wantsUpload)requestEnable()
            else if(!audioReadable)audioPermission.launch(SystemRecordingMediaStore.permission())
            else {CallRecordingJobService.wake(this);Toast.makeText(this,"已安排查找，空闲时后台执行",Toast.LENGTH_SHORT).show()}
        }
        button("高级：补充未发现的目录（可选）"){
            AlertDialog.Builder(this).setTitle("仅在自动查找遗漏时使用")
                .setItems(arrayOf("dh目录","wx目录")){_,which->chooseDirectory(if(which==0)"phone"else"wechat")}
                .setNegativeButton("取消",null).show()
        }
        callLogSwitch=SwitchCompat(this).apply{
            text="同步最近7天输入记录"
            isChecked=PhoneCallLogRuntime.consent(this@CallRecordingSettingsActivity).enabled
            box.addView(this)
            setOnCheckedChangeListener{_,checked->if(!updatingCallLog&&!changingCallLog){
                if(checked)enableCallLog()else{
                    PhoneCallLogRuntime.consent(this@CallRecordingSettingsActivity).revoke()
                    PhoneCallLogRuntime.restore(this@CallRecordingSettingsActivity);refreshStatus()
                }
            }}
        }
        callLogStatus=text("")
        button("授权并立即同步"){
            if(!PhoneCallLogRuntime.consent(this).enabled){
                Toast.makeText(this,"请先开启上方同步开关",Toast.LENGTH_LONG).show()
            }else if(!PhoneCallLogRuntime.hasPermission(this))callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
            else PhoneCallLogJobService.wake(this)
        }
        button("返回"){finish()}
    }
    private fun enableCallLog(){
        if(!CollectionConsent.enabled(this)){
            Toast.makeText(this,"请先开启个人数据同步总开关",Toast.LENGTH_LONG).show();refreshStatus();return
        }
        val target=ServerConfig.baseUrl;val ticket=PhoneCallLogRuntime.consent(this).revision
        changingCallLog=true;callLogSwitch.isEnabled=false
        var accepted=false
        AlertDialog.Builder(this).setTitle("同步输入")
            .setNegativeButton("取消",null)
            .setPositiveButton("同意并开启"){_,_->accepted=true;enableCallLogConfirmed(target,ticket)}
            .setOnDismissListener{if(!accepted){changingCallLog=false;callLogSwitch.isEnabled=true;refreshStatus()}}
            .show()
    }
    private fun enableCallLogConfirmed(target:String,ticket:Long){
        lifecycleScope.launch{
            try{
                val device=withContext(Dispatchers.IO){DataCollector.deviceId(this@CallRecordingSettingsActivity)}
                if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)||!CollectionConsent.enabled(this@CallRecordingSettingsActivity)||ServerConfig.baseUrl!=target)return@launch
                val granted=withContext(Dispatchers.IO){PhoneCallLogRuntime.consent(this@CallRecordingSettingsActivity).grant(device,target,ticket)}
                if(granted){
                    PhoneCallLogRuntime.restore(this@CallRecordingSettingsActivity)
                    if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){
                        if(PhoneCallLogRuntime.hasPermission(this@CallRecordingSettingsActivity))PhoneCallLogJobService.wake(this@CallRecordingSettingsActivity)
                        else callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
                    }
                }
            }catch(e:CancellationException){throw e}
            catch(_:Exception){Toast.makeText(this@CallRecordingSettingsActivity,"同步未开启，请重试",Toast.LENGTH_LONG).show()}
            finally{changingCallLog=false;callLogSwitch.isEnabled=true;refreshStatus()}
        }
    }
    private fun requestManualWechatRecording(){
        val consent=CallRecordingRuntime.consent(this)
        if(!consent.wantsRecording||!consent.hasExpandedRecordingScope){requestEnable();return}
        var video=false
        AlertDialog.Builder(this).setTitle("选择当前已接通的类型")
            .setSingleChoiceItems(arrayOf("语音","视频"),0){_,which->video=which==1}
            .setView(TextView(this).apply{
                setPadding(48,16,48,24)
            })
            .setNegativeButton("取消",null)
            .setPositiveButton("开始尝试"){_,_->CallRecordingService.startWechatFromActivity(this,video);refreshStatus()}
            .show()
    }
    private fun chooseDirectory(platform:String) {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if(Build.VERSION.SDK_INT>=26 && Build.MANUFACTURER.equals("HONOR",ignoreCase=true)) {
            val directory="primary:Sounds/CallRecord"
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",directory))
        }
        try{if(platform=="phone")phoneDirectory.launch(intent)else wechatDirectory.launch(intent)}
        catch(_:Exception){Toast.makeText(this,"无法打开系统目录选择器",Toast.LENGTH_LONG).show()}
    }
    private fun saveDirectory(platform:String,result:Int,data:Intent?) {
        if(result!=RESULT_OK)return
        val uri=data?.data ?: return
        lifecycleScope.launch {
            val success=withContext(Dispatchers.IO){runCatching{SystemRecordingDocuments(this@CallRecordingSettingsActivity).setTree(platform,uri)}.isSuccess}
            if(success){CallRecordingJobService.wake(this@CallRecordingSettingsActivity);refreshSourceAccess()}
            else Toast.makeText(this@CallRecordingSettingsActivity,"目录无法授权，或不支持共用该目录；请选择对应目录",Toast.LENGTH_LONG).show()
        }
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
                AlertDialog.Builder(this@CallRecordingSettingsActivity).setTitle("电话 / 微信录音与上传授权")
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
                if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)||!CollectionConsent.enabled(this@CallRecordingSettingsActivity)||ServerConfig.baseUrl!=target){finishChange();return@launch}
                val granted=withContext(Dispatchers.IO){CallRecordingRuntime.consent(this@CallRecordingSettingsActivity).grantCombined(device,target,ticket)}
                if(!granted){finishChange();return@launch}
                CallRecordingRuntime.preferences(this@CallRecordingSettingsActivity).edit().putBoolean("capability_blocked",false).apply()
                CallRecordingRuntime.restore(this@CallRecordingSettingsActivity)
                finishChange()
                if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)&&CallRecordingRuntime.consent(this@CallRecordingSettingsActivity).recordingAllowed(device,target)){
                    val needed=mutableListOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE,SystemRecordingMediaStore.permission())
                    permission.launch(needed.toTypedArray())
                }
            }catch(e:CancellationException){throw e}
            catch(_:Exception){finishChange();Toast.makeText(this@CallRecordingSettingsActivity,"开启失败，请查看当前状态",Toast.LENGTH_LONG).show()}
        }
    }
    private fun refreshStatus(){
        syncSwitch()
        if(::callLogSwitch.isInitialized&&!changingCallLog){
            updatingCallLog=true;callLogSwitch.isChecked=PhoneCallLogRuntime.consent(this).enabled;updatingCallLog=false
            callLogStatus.text=when{
                !callLogSwitch.isChecked->"同步已关闭"
                !CollectionConsent.enabled(this)->"个人数据同步总开关关闭"
                !PhoneCallLogRuntime.hasPermission(this)->"尚未获得系统权限，请点击下方授权；若系统拒绝，请到应用权限中检查"
                else->PhoneCallLogRuntime.preferences(this).getString("status","已开启，等待后台同步")
            }
        }
        if(CallRecordingService.activeId!=null)stopPreview()
        if(!::status.isInitialized)return
        val consent=CallRecordingRuntime.consent(this);val p=CallRecordingRuntime.preferences(this)
        val permissionBlock=if(consent.wantsRecording)CallRecordingService.permissionBlock(this) else null
        val state=permissionBlock ?: (p.getString("state","")?:"")
        val labels=mapOf("waiting_incoming" to "等待普通来电（尚未确认呼出 / 微信自录授权）",
            "waiting_calls" to "当前没有数据",
            "recording_unverified" to "尚未验证",
            "outgoing_unverified" to "正在尝试拨出，质量未验证",
            "waiting_wechat_evidence" to "等待状态",
            "wechat_call_not_active" to "未检测到，请先接通再尝试",
            "wechat_call_waiting_audio" to "检测到通知，等待状态确认",
            "retry_next_call" to "本次未成功，等待下一次重试",
            "system_silenced" to "系统将静音，本次已停止，下次重新尝试",
            "suspected_silent" to "持续无有效数据，本次已停止，下次重新尝试",
            "microphone_unavailable" to "不可用，本次已停止，下次重新尝试",
            "single_sim_required" to "旧单卡限制已更新，等待重新建立",
            "no_active_sim" to "未发现活动 SIM，尚未开始",
            "legacy_multi_sim_unsupported" to "Android 6 不支持本实现的逐卡监听",
            "sim_changed" to "SIM 状态改变，已停止；返回此设置页后重建监听",
            "overlapping_calls" to "两卡并发或呼叫等待，已停止，等待全部空闲",
            "notifications_required" to "旧通知限制已更新，等待前台恢复",
            "notification_channel_required" to "旧通知限制已更新，等待前台恢复",
            "audio_phone_permissions_required" to "麦克风或电话权限未允许，请关闭再打开开关授权","foreground_restricted" to "系统限制后台麦克风启动",
            "permissions_or_sync_required" to "权限或同步总开关不可用，当前暂停","setup_restricted" to "授权目标/设备或系统条件不满足",
            "phone_state_unavailable" to "无法订阅状态","recorder_error" to "录音异常，本次已停止","local_finalize_pending" to "文件已保留，等待本地恢复",
            "limit_reached" to "达到单次上限，已停止")
        val lastFailure=p.getString("last_failure",null)?.takeIf{it.isNotBlank()}
        status.text="自录选择：${if(consent.wantsRecording)"已记住开启"else"关闭"}\n"+
            "自录授权范围：${if(consent.hasExpandedRecordingScope)"dh / wx"else"扩展范围请点击下方确认"}\n"+
            "上传授权：${if(consent.wantsUpload)"开启"else"关闭"}\n上传目标：${consent.destination.ifBlank{"未授权"}}\n"+
            "当前：${if(CallRecordingService.isRunning)labels[state]?:state.ifBlank{"准备中"} else if(consent.wantsRecording)labels[state]?.let{"$it；服务未运行"}?:"未运行，等待前台权限条件" else"无数据"}"+
            (lastFailure?.let{"\n最近一次无数据原因：${labels[it]?:it}"}?:"")
        if(::systemStatus.isInitialized) {
            systemStatus.text="系统读取：${if(!sourceAccessLoaded)"正在检查权限"else if(audioReadable)"已允许"else"未允许，可点击下方授权"}\n"+
                listOf("phone" to "phone","wechat" to "wx").joinToString("\n"){(platform,label)->
                    "$label：${if(!consent.wantsUpload)"上传已关闭"else if(!sourceAccessLoaded)"正在检查权限"else if(platform !in readableSources)"等待读取授权"else p.getString("system_scan_$platform","已就绪，等待后台自动查找")}"}
        }
    }
    private fun refreshSourceAccess(){
        sourceAccessJob?.cancel()
        sourceAccessJob=lifecycleScope.launch {
            val access=withContext(Dispatchers.IO){
                val sources=SystemRecordingSources(this@CallRecordingSettingsActivity)
                SystemRecordingMediaStore(this@CallRecordingSettingsActivity).readable() to
                    listOf("phone","wechat").filter{sources.readable(it)}.toSet()
            }
            audioReadable=access.first;readableSources=access.second;sourceAccessLoaded=true
            refreshStatus()
        }
    }
    private fun refreshCounts(){lifecycleScope.launch {
        val value=withContext(Dispatchers.IO){runCatching {
            val box=CallRecordingRuntime.outbox(this@CallRecordingSettingsActivity);val tasks=box.tasks()
            "待传/暂停/失败：${tasks.count{it.uploadStatus !in listOf("saved","superseded")}}；已保存待清理：${tasks.count{it.uploadStatus=="saved"&&it.cleanupStatus!="deleted"}}；重复副本已清理：${tasks.count{it.uploadStatus=="superseded"}}\n中断/待封装：${CallRecordingRuntime.sessions(this@CallRecordingSettingsActivity).pendingCount()}；坏清单：${box.invalidEntries().size}。中断文件不自动删除。"
        }.onFailure{android.util.Log.e("CallRecordingSettings","Unable to read local recording state",it)}.getOrDefault("本地状态读取失败，文件保留")}
        counts.text=value
    }}
    private fun preview(){
        if(CallRecordingService.activeId!=null){Toast.makeText(this,"不播放，避免影响使用",Toast.LENGTH_LONG).show();return}
        stopPreview()
        val epoch=previewEpoch
        previewJob=lifecycleScope.launch {
            val file=withContext(Dispatchers.IO){runCatching{val b=CallRecordingRuntime.outbox(this@CallRecordingSettingsActivity);b.tasks().sortedByDescending{it.metadata.recording_started_at}.map{b.audioFile(it.id)}.firstOrNull{it.isFile}}.getOrNull()}
            if(!previewAllowed(epoch))return@launch
            if(file==null){Toast.makeText(this@CallRecordingSettingsActivity,"暂无可用记录，已清理的请在后台试听",Toast.LENGTH_LONG).show();return@launch}
            player?.release()
            try{player=MediaPlayer().apply{setDataSource(file.absolutePath);setOnPreparedListener{if(previewAllowed(epoch))it.start() else {it.release();if(player===it)player=null}};setOnErrorListener{_,_,_->Toast.makeText(this@CallRecordingSettingsActivity,"无法播放，不能认定成功",Toast.LENGTH_LONG).show();true};prepareAsync()}}
            catch(_:Exception){player?.release();player=null;Toast.makeText(this@CallRecordingSettingsActivity,"无法播放",Toast.LENGTH_LONG).show()}
        }
    }
    private fun previewAllowed(epoch:Long)=callPreviewAllowed(epoch,previewEpoch,
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),CallRecordingService.activeId!=null)
    private fun stopPreview(){previewEpoch++;previewJob?.cancel();previewJob=null;player?.release();player=null}
    override fun onResume(){super.onResume();PhoneCallLogRuntime.restore(this);PhoneCallLogJobService.wake(this);CallRecordingService.restoreFromActivity(this);CallRecordingJobService.wake(this);main.post(ticker);refreshSourceAccess();refreshCounts()}
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
