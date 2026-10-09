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
        super.onCreate(savedInstanceState);title="通话录音与上传";ServerConfig.init(this)
        enableEdgeToEdge()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,36,32,36)}
        val scroll=ScrollView(this).apply{addView(box)}
        applyCallRecordingWindowInsets(scroll)
        setContentView(scroll)
        fun text(value:String)=TextView(this).apply{text=value;textSize=16f;setPadding(0,12,0,12);box.addView(this)}
        fun button(label:String,action:()->Unit){box.addView(Button(this).apply{text=label;setOnClickListener{action()}},ViewGroup.LayoutParams(-1,-2))}
        automatic=SwitchCompat(this).apply {
            text="电话 / 微信录音与上传"
            val consent=CallRecordingRuntime.consent(this@CallRecordingSettingsActivity)
            isChecked=consent.wantsRecording||consent.wantsUpload
            box.addView(this)
        }
        text("尝试录制普通来电、拨出电话和微信语音 / 视频通话的麦克风声音，并自动查找系统已有的电话 / 微信录音。Android 可能限制通话期间的麦克风，不能保证录到双方声音；录音是否可用需实际试听，未录成会显示原因。请在通话参与者知情的情况下使用。")
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
        button("确认或扩展电话 / 微信自录授权"){requestEnable()}
        text("微信自动识别需通知读取权限，且微信提供持续的正在通话通知；没有这类通知时，可先接通微信，再返回这里手动尝试录音。系统回收服务后需进入本页恢复，不能绕过后台麦克风限制。")
        button("微信通话识别：通知读取授权"){
            try{startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}
            catch(_:Exception){Toast.makeText(this,"无法打开通知读取设置，请到系统特殊权限中授权",Toast.LENGTH_LONG).show()}
        }
        button("录制当前微信通话"){requestManualWechatRecording()}
        text("允许读取音频后自动查找最近7天的电话 / 微信录音，无需选择文件夹；以前授予的目录也会继续使用。系统未公开的录音可能无法自动发现。只通过有效 Wi-Fi 上传到已同意的服务器，系统原件不删除；输入法自录副本在服务器确认保存后清理。关闭上方开关即可停止自录和后续上传，待传文件仍保留。")
        systemStatus=text("")
        button("授权并立即查找"){
            if(!CallRecordingRuntime.consent(this).wantsUpload)requestEnable()
            else if(!audioReadable)audioPermission.launch(SystemRecordingMediaStore.permission())
            else {CallRecordingJobService.wake(this);Toast.makeText(this,"已安排查找，空闲时后台执行",Toast.LENGTH_SHORT).show()}
        }
        button("高级：补充未发现的目录（可选）"){
            AlertDialog.Builder(this).setTitle("仅在自动查找遗漏时使用")
                .setItems(arrayOf("电话录音目录","微信录音目录")){_,which->chooseDirectory(if(which==0)"phone"else"wechat")}
                .setNegativeButton("取消",null).show()
        }
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
        text("通话记录单独授权：读取号码、系统已有的联系人名称、呼入 / 呼出 / 未接类型、时间和时长并上传；不包含录音声音，也不读取微信通话历史。关闭此开关即可停止后续同步。")
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
        AlertDialog.Builder(this).setTitle("同步手机通话记录")
            .setMessage("将读取最近7天系统电话的号码、系统已有联系人名称、呼入 / 呼出 / 未接类型、通话时间和时长，并上传到：\n$target\n\n不包含录音声音或微信通话历史；可随时关闭本页的通话记录同步开关停止后续同步。")
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
        AlertDialog.Builder(this).setTitle("选择当前已接通的微信通话类型")
            .setSingleChoiceItems(arrayOf("语音通话","视频通话"),0){_,which->video=which==1}
            .setView(TextView(this).apply{
                text="请先在微信接通通话，再返回本页开始尝试。只录系统允许访问的麦克风声音，不录制视频画面，不能保证录到双方声音；挂断后停止。录音将按本页已授权规则通过 Wi-Fi 上传。"
                setPadding(48,16,48,24)
            })
            .setNegativeButton("取消",null)
            .setPositiveButton("开始尝试录音"){_,_->CallRecordingService.startWechatFromActivity(this,video);refreshStatus()}
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
                    .setMessage("同意后将尝试录制普通来电、拨出电话及微信语音 / 视频通话，并查找最近7天系统公开的电话 / 微信录音。录音声音、文件信息及通话时间将上传到：\n$target\n\n仅有效 Wi-Fi 上传，后台已有的录音不重复上传。系统原件不删除；输入法自录副本仅在服务器确认保存或确认同通话系统录音已保存且原件仍在时删除。\n\nAndroid 可能使麦克风不可用或静音，不能保证录到双方声音；请在参与者知情时使用。自录使用系统前台服务提示，不绕过系统限制。\n\n可以随时关闭本页主开关，停止后续自录和上传；待传文件保留。手机通话记录需另行开启下方开关。")
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
                !callLogSwitch.isChecked->"通话记录同步已关闭"
                !CollectionConsent.enabled(this)->"个人数据同步总开关关闭"
                !PhoneCallLogRuntime.hasPermission(this)->"尚未获得系统通话记录权限，请点击下方授权；若系统拒绝，请到应用权限中检查"
                else->PhoneCallLogRuntime.preferences(this).getString("status","已开启，等待后台同步")
            }
        }
        if(CallRecordingService.activeId!=null)stopPreview()
        if(!::status.isInitialized)return
        val consent=CallRecordingRuntime.consent(this);val p=CallRecordingRuntime.preferences(this)
        val permissionBlock=if(consent.wantsRecording)CallRecordingService.permissionBlock(this) else null
        val state=permissionBlock ?: (p.getString("state","")?:"")
        val labels=mapOf("waiting_incoming" to "等待普通来电（尚未确认呼出 / 微信自录授权）",
            "waiting_calls" to "等待电话 / 微信通话；当前没有录音",
            "recording_unverified" to "正在录音，声音质量及双方音轨尚未验证",
            "outgoing_unverified" to "正在尝试拨出电话录音，接通时间和声音质量未验证",
            "waiting_wechat_evidence" to "等待微信正在通话的有效通知及系统音频状态",
            "wechat_call_not_active" to "未检测到正在进行的微信通话，请先接通再尝试",
            "wechat_call_waiting_audio" to "检测到微信通话通知，等待通话音频状态确认",
            "retry_next_call" to "本次未录成，等待下一次通话重试",
            "system_silenced" to "系统将录音静音，本次已停止，下次通话重新尝试",
            "suspected_silent" to "持续无有效声音，本次已停止，下次通话重新尝试",
            "microphone_unavailable" to "麦克风不可用，本次已停止，下次通话重新尝试",
            "single_sim_required" to "旧单卡限制已更新，等待重新建立",
            "no_active_sim" to "未发现活动 SIM，尚未开始",
            "legacy_multi_sim_unsupported" to "Android 6 不支持本实现的逐卡监听",
            "sim_changed" to "SIM 状态改变，已停止；返回此设置页后重建监听",
            "overlapping_calls" to "两卡并发或呼叫等待，已停止，等待全部空闲",
            "notifications_required" to "旧通知限制已更新，等待前台恢复",
            "notification_channel_required" to "旧通知限制已更新，等待前台恢复",
            "audio_phone_permissions_required" to "麦克风或电话权限未允许，请关闭再打开开关授权","foreground_restricted" to "系统限制后台麦克风启动",
            "permissions_or_sync_required" to "权限或同步总开关不可用，当前暂停","setup_restricted" to "授权目标/设备或系统条件不满足",
            "phone_state_unavailable" to "无法订阅通话状态","recorder_error" to "录音异常，本次已停止","local_finalize_pending" to "文件已保留，等待本地恢复",
            "limit_reached" to "达到单次上限，已停止")
        val lastFailure=p.getString("last_failure",null)?.takeIf{it.isNotBlank()}
        status.text="自录选择：${if(consent.wantsRecording)"已记住开启"else"关闭"}\n"+
            "自录授权范围：${if(consent.hasExpandedRecordingScope)"电话来电 / 呼出及微信通话"else"仅普通来电；扩展范围请点击下方确认"}\n"+
            "上传授权：${if(consent.wantsUpload)"开启"else"关闭"}\n上传目标：${consent.destination.ifBlank{"未授权"}}\n"+
            "当前：${if(CallRecordingService.isRunning)labels[state]?:state.ifBlank{"准备中"} else if(consent.wantsRecording)labels[state]?.let{"$it；服务未运行"}?:"未运行，等待前台权限条件" else"未录音"}"+
            (lastFailure?.let{"\n最近一次未录成原因：${labels[it]?:it}"}?:"")
        if(::systemStatus.isInitialized) {
            systemStatus.text="系统音频读取：${if(!sourceAccessLoaded)"正在检查权限"else if(audioReadable)"已允许"else"未允许，可点击下方授权"}\n"+
                listOf("phone" to "电话","wechat" to "微信").joinToString("\n"){(platform,label)->
                    "$label：${if(!consent.wantsUpload)"上传已关闭"else if(!sourceAccessLoaded)"正在检查权限"else if(platform !in readableSources)"等待音频读取授权"else p.getString("system_scan_$platform","已就绪，等待后台自动查找")}"}
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
        if(CallRecordingService.activeId!=null){Toast.makeText(this,"不播放，避免影响通话",Toast.LENGTH_LONG).show();return}
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
