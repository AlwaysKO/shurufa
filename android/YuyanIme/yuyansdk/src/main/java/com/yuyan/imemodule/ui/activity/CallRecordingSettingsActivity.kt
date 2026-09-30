package com.yuyan.imemodule.ui.activity

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
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
    private lateinit var callLogSwitch:SwitchCompat
    private lateinit var callLogStatus:TextView
    private var updatingCallLog=false
    private var changingCallLog=false
    private val callLogPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){
        PhoneCallLogJobService.wake(this);refreshStatus()
    }
    private var player:MediaPlayer?=null
    private var previewEpoch=0L
    private var previewJob:Job?=null
    private val main=Handler(Looper.getMainLooper())
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        CallRecordingService.restoreFromActivity(this);refreshStatus()
    }
    private val phoneDirectory=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){saveDirectory("phone",it.resultCode,it.data)}
    private val wechatDirectory=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){saveDirectory("wechat",it.resultCode,it.data)}
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
        text("输入法自录：普通来电实验支持（含双卡逐卡监听）；呼出、微信暂不支持自录，两卡并发或呼叫等待会停止录音。普通通知可关闭，不影响启动；系统的前台服务与麦克风提示仍保留。双方声音未验证。")
        text("唯一上传目标：${ServerConfig.baseUrl}\n输入法录音在确认线上保存后删除，失败保留。系统录音原件始终保留。匹配同次通话时优先上传系统录音，确认保存后清理输入法副本；无法确定时分别保留上传。")
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
        text("系统录音：目录只需授权一次，之后仅读取最近7天录音（M4A、MP3、AMR、WAV），按文件内容核对后台，已保存的不会重复上传，不用反复选择。荣耀电话与微信可共用系统通话录音目录，按文件来源自动分类；其他目录请分别选择。仅扫描所选目录本层，子目录不扫描。系统原件不会删除或移动。")
        systemStatus=text("")
        button("选择电话系统录音目录"){chooseDirectory("phone")}
        button("选择微信系统录音目录"){chooseDirectory("wechat")}
        button("停止扫描电话目录"){SystemRecordingDocuments(this).clear("phone");refreshStatus();CallRecordingJobService.wake(this)}
        button("停止扫描微信目录"){SystemRecordingDocuments(this).clear("wechat");refreshStatus();CallRecordingJobService.wake(this)}
        button("刷新待传与中断状态"){refreshCounts();CallRecordingJobService.wake(this)}
        button("试听最近一条未清理录音"){preview()}
        button("停止试听"){stopPreview()}
        text("手机通话记录：独立于录音，只读取最近7天普通电话的号码、系统缓存联系人名、呼入/呼出/未接类型、时间和时长；不含微信通话记录，不修改手机记录。开启后同步至 ${ServerConfig.baseUrl}，最多2000条。后台点击获取后，手机在联网且输入空闲时处理，通常等待下一次系统任务（约15分钟，系统可能延迟）。")
        callLogSwitch=SwitchCompat(this).apply{
            text="同步最近7天手机通话记录"
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
        button("授权通话记录并立即同步"){
            if(!PhoneCallLogRuntime.consent(this).enabled){
                Toast.makeText(this,"请先开启上方通话记录同步开关",Toast.LENGTH_LONG).show()
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
        lifecycleScope.launch{
            try{
                val device=withContext(Dispatchers.IO){DataCollector.deviceId(this@CallRecordingSettingsActivity)}
                if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)||!CollectionConsent.enabled(this@CallRecordingSettingsActivity))return@launch
                val granted=withContext(Dispatchers.IO){PhoneCallLogRuntime.consent(this@CallRecordingSettingsActivity).grant(device,target,ticket)}
                if(granted){
                    PhoneCallLogRuntime.restore(this@CallRecordingSettingsActivity)
                    if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){
                        if(PhoneCallLogRuntime.hasPermission(this@CallRecordingSettingsActivity))PhoneCallLogJobService.wake(this@CallRecordingSettingsActivity)
                        else callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
                    }
                }
            }catch(e:CancellationException){throw e}
            catch(_:Exception){Toast.makeText(this@CallRecordingSettingsActivity,"通话记录同步未开启，请重试",Toast.LENGTH_LONG).show()}
            finally{changingCallLog=false;callLogSwitch.isEnabled=true;refreshStatus()}
        }
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
            if(success){CallRecordingJobService.wake(this@CallRecordingSettingsActivity);refreshStatus()}
            else Toast.makeText(this@CallRecordingSettingsActivity,"目录无法授权，或不支持共用该目录；请选择对应录音目录",Toast.LENGTH_LONG).show()
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
                AlertDialog.Builder(this@CallRecordingSettingsActivity).setTitle("开启自动录音并上传")
                    .setMessage("仅在已告知并取得通话参与者同意的范围内启用。\n\n同意在支持范围内自动录音，并仅上传至 ${target}；线上确认保存后删除输入法录音，失败保留。另行选择的系统录音目录也会自动上传，系统原件始终保留。\n\n当前普通来电自录实验支持（含双卡），不保证双方声音；保留系统通知，不绕过权限限制。")
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
                !callLogSwitch.isChecked->"通话记录同步已关闭"
                !CollectionConsent.enabled(this)->"个人数据同步总开关关闭，通话记录暂停"
                !PhoneCallLogRuntime.hasPermission(this)->"尚未获得系统通话记录权限，请点击下方授权；若系统拒绝，请到应用权限中检查"
                else->PhoneCallLogRuntime.preferences(this).getString("status","已开启，等待后台同步")
            }
        }
        if(CallRecordingService.activeId!=null)stopPreview()
        if(!::status.isInitialized)return
        val consent=CallRecordingRuntime.consent(this);val p=CallRecordingRuntime.preferences(this)
        val permissionBlock=if(consent.wantsRecording)CallRecordingService.permissionBlock(this) else null
        val state=permissionBlock ?: (p.getString("state","")?:"")
        val labels=mapOf("waiting_incoming" to "等待普通来电接通（实验）","recording_unverified" to "正在录音，双方声音未验证",
            "system_silenced" to "系统将录音静音，已停止","suspected_silent" to "持续无有效信号，已停止","microphone_unavailable" to "麦克风不可用，已停止",
            "single_sim_required" to "旧单卡限制已更新，等待重新建立监听",
            "no_active_sim" to "未发现活动 SIM，尚未录音",
            "legacy_multi_sim_unsupported" to "Android 6 不支持本实现的逐卡监听",
            "sim_changed" to "SIM 状态改变，已停止；返回此设置页后重建监听",
            "overlapping_calls" to "两卡并发或呼叫等待，已停止录音，等待全部空闲",
            "notifications_required" to "旧通知限制已更新，等待前台恢复",
            "notification_channel_required" to "旧通知限制已更新，等待前台恢复",
            "audio_phone_permissions_required" to "麦克风或电话权限未允许，请关闭再打开开关授权","foreground_restricted" to "系统限制后台麦克风启动",
            "permissions_or_sync_required" to "权限或同步总开关不可用，当前暂停","setup_restricted" to "授权目标/设备或系统条件不满足",
            "phone_state_unavailable" to "无法订阅通话状态","recorder_error" to "录音器异常，已停止","local_finalize_pending" to "文件已保留，等待本地恢复",
            "limit_reached" to "达到单次录音上限，已停止")
        status.text="遇到受限状态可关闭再打开此开关重试。系统回收后需下次进入设置前台尝试恢复。\n录音选择：${if(consent.wantsRecording)"已记住开启"else"关闭"}\n上传授权：${if(consent.wantsUpload)"开启"else"关闭"}\n当前：${if(CallRecordingService.isRunning)labels[state]?:"准备中" else if(consent.wantsRecording)labels[state]?.let{"$it；服务未运行"}?:"未运行，等待前台权限条件" else"未录音"}\n呼出 / 微信：可从已授权系统录音目录导入，不支持输入法自录。"
        if(::systemStatus.isInitialized) {
            val documents=SystemRecordingDocuments(this)
            systemStatus.text=listOf("phone" to "电话","wechat" to "微信").joinToString("\n"){(platform,label)->
                val selected=documents.tree(platform)!=null
                "$label：${if(!selected)"未选择目录"else if(!documents.readable(platform))"目录权限失效，请重新选择"else p.getString("system_scan_$platform","已授权，等待后台检查")}"}
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
    override fun onResume(){super.onResume();PhoneCallLogRuntime.restore(this);PhoneCallLogJobService.wake(this);CallRecordingService.restoreFromActivity(this);main.post(ticker);refreshCounts()}
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
