package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.AudioManager
import android.os.*
import android.telephony.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.ui.activity.CallRecordingSettingsActivity

/** 明确授权后的通话自录尝试；不将麦克风启动成功当作双方收音成功。 */
class CallRecordingService:Service() {
    private lateinit var thread:HandlerThread
    private lateinit var worker:Handler
    private val main=Handler(Looper.getMainLooper())
    private lateinit var policy:MultiSimIncomingCallPolicy
    private var observedSubscriptions=emptySet<Int>()
    private data class PhoneWatch(val manager:TelephonyManager,val callback:TelephonyCallback?=null,val legacy:PhoneStateListener?=null)
    private val watches=mutableListOf<PhoneWatch>()
    private var recorder:MediaRecorder?=null
    private var task:CallTask?=null
    private var device=""
    private var recordingMono=0L
    private var callMono=0L
    private var signal=false
    private var wechatConnection:AutoCloseable?=null
    private var modeListener:AudioManager.OnModeChangedListener?=null
    private var wechatSource:String?=null
    private var wechatPendingUntil=0L
    private var wechatAttempted=false
    @Volatile private var closing=false
    private val prefs get()=CallRecordingRuntime.preferences(this)
    private val sessions get()=CallRecordingRuntime.sessions(this)
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate();thread=HandlerThread("call-recording-control",Process.THREAD_PRIORITY_BACKGROUND).apply{start()};worker=Handler(thread.looper)}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){CallRecordingRuntime.consent(this).revokeAll();CallRecordingJobService.cancel(this);stopSelf();return START_NOT_STICKY}
        // 系统重新创建不是一次前台授权：只恢复上传，不能在后台偷偷恢复麦克风。
        if(intent?.action!=START){status("needs_foreground");stopSelf();return START_NOT_STICKY}
        if(isRunning){if(intent.getBooleanExtra(MANUAL_WECHAT,false))worker.post{manualWechat(intent.getBooleanExtra(WECHAT_VIDEO,false))};return START_NOT_STICKY}
        if(!permissions(this)||!CollectionConsent.enabled(this)){status("permissions_or_sync_required");stopSelf();return START_NOT_STICKY}
        try{createChannel();startForeground(NOTIFICATION_ID,notification("已授权"))}
        catch(_:Exception){status("foreground_restricted");stopSelf();return START_NOT_STICKY}
        isRunning=true
        worker.post {
            if(closing)return@post
            try{
                ServerConfig.init(this);device=DataCollector.deviceId(this)
                if(!authorized())throw IllegalStateException()
                observedSubscriptions=activeSubscriptions()
                if(Build.VERSION.SDK_INT<24&&observedSubscriptions.size>1){status("legacy_multi_sim_unsupported");stopSelf();return@post}
                if(observedSubscriptions.isNotEmpty())policy=MultiSimIncomingCallPolicy(observedSubscriptions)
                prefs.edit().remove("capability_blocked").apply()
                status(waitingState())
                wechatConnection=WechatCallSignals.connect{active,source->worker.post{wechatSignal(active,source)}}
                main.post{
                    if(observedSubscriptions.isNotEmpty())registerPhone()
                    if(!closing&&Build.VERSION.SDK_INT>=31){
                        val listener=AudioManager.OnModeChangedListener{worker.post{checkWechat()}}
                        modeListener=listener
                        runCatching{(getSystemService(AUDIO_SERVICE) as AudioManager).addOnModeChangedListener(mainExecutor,listener)}
                    }
                }
                if(intent.getBooleanExtra(MANUAL_WECHAT,false))manualWechat(intent.getBooleanExtra(WECHAT_VIDEO,false))
                worker.post(health)
            }catch(_:Exception){status("setup_restricted");stopSelf()}
        }
        return START_NOT_STICKY
    }
    @SuppressLint("MissingPermission")
    private fun registerPhone(){
        if(closing)return
        try{
            val base=getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            for(subscription in observedSubscriptions){
                val manager=if(Build.VERSION.SDK_INT>=24)base.createForSubscriptionId(subscription)else base
                if(Build.VERSION.SDK_INT>=31){
                    val listener=object:TelephonyCallback(),TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state:Int){worker.post{phoneState(subscription,state)}}
                    }
                    watches.add(PhoneWatch(manager,callback=listener))
                    manager.registerTelephonyCallback(mainExecutor,listener)
                }else{
                    val listener=object:PhoneStateListener(){
                        override fun onCallStateChanged(state:Int,number:String?){worker.post{phoneState(subscription,state)}}
                    }
                    watches.add(PhoneWatch(manager,legacy=listener))
                    manager.listen(listener,PhoneStateListener.LISTEN_CALL_STATE)
                }
            }
        }catch(_:Exception){status("phone_state_unavailable");stopSelf()}
    }
    @SuppressLint("MissingPermission")
    private fun activeSubscriptions():Set<Int> =
        (getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager)
            .activeSubscriptionInfoList.orEmpty().map{it.subscriptionId}.toSet()
    private fun subscriptionsStable():Boolean = runCatching{activeSubscriptions()==observedSubscriptions}.getOrDefault(false)
    private fun stopForSubscriptionChange(){
        stopRecording(false,"sim_changed");status("sim_changed");stopSelf()
    }
    private fun phoneState(subscription:Int,state:Int){
        if(closing)return
        if(!authorized()||!permissions(this)){stopRecording(false,"authorization_or_permission_changed");stopSelf();return}
        if(!subscriptionsStable()){stopForSubscriptionChange();return}
        if(state!=TelephonyManager.CALL_STATE_IDLE&&wechatSource!=null){
            wechatAttempted=true;wechatPendingUntil=0
            if(task?.metadata?.platform=="wechat")stopRecording(false,"overlapping_calls")
        }
        when(policy.update(subscription,state,expandedAllowed())){
            CallTransition.START->startRecording("phone",!policy.activeOutgoing)
            CallTransition.END->{stopRecording(true,null);if(authorized())status(waitingState())}
            CallTransition.INTERRUPT->{stopRecording(false,"overlapping_calls");status("overlapping_calls")}
            else->Unit
        }
        checkWechat()
    }
    private fun phonesIdle()=observedSubscriptions.isEmpty()||(::policy.isInitialized&&policy.allIdle)
    private fun communicationMode()=(getSystemService(AUDIO_SERVICE) as AudioManager).mode==AudioManager.MODE_IN_COMMUNICATION
    private fun expandedAllowed()=CallRecordingRuntime.consent(this).expandedRecordingAllowed(device,ServerConfig.baseUrl)
    private fun waitingState()=if(expandedAllowed())"waiting_calls" else "waiting_incoming"
    private fun wechatSignal(active:Boolean,source:String){
        if(closing||source !in listOf("wechat_voice","wechat_video"))return
        if(!active){if(wechatSource!=null)endWechat(null);return}
        if(!authorized()||!expandedAllowed()||!permissions(this)||!subscriptionsStable())return
        // 重复通知不能重试失败的同一通，也不能延长候选有效期。
        if(wechatSource!=null)return
        wechatSource=source;wechatAttempted=false
        wechatPendingUntil=SystemClock.elapsedRealtime()+30_000
        prefs.edit().putString("signal_observed",source).apply()
        checkWechat()
    }
    private fun manualWechat(video:Boolean){
        if(!authorized()||!expandedAllowed()||!permissions(this)){status("permissions_or_sync_required");return}
        if(!communicationMode()){status("wechat_call_not_active");return}
        if(recorder!=null)return
        // 仅用户再次点击可主动重试；通知重复不享有此入口。
        wechatSource=null;wechatPendingUntil=0;wechatAttempted=false
        wechatSignal(true,if(video)"wechat_video"else"wechat_voice")
    }
    private fun checkWechat(){
        if(closing||wechatSource==null)return
        if(!authorized()||!expandedAllowed()||!permissions(this)){endWechat("authorization_or_permission_changed");return}
        if(!subscriptionsStable()){stopForSubscriptionChange();return}
        val communicating=communicationMode()
        if(wechatAttempted){
            if(!communicating)endWechat(null)
            return
        }
        if(SystemClock.elapsedRealtime()>=wechatPendingUntil){
            wechatPendingUntil=0;wechatAttempted=true;status("wechat_call_not_active");scheduleHealth();return
        }
        if(communicating&&phonesIdle()&&recorder==null){
            wechatAttempted=true;wechatPendingUntil=0
            startRecording(wechatSource!!,false)
        }else{status("wechat_call_waiting_audio");scheduleHealth()}
    }
    private fun endWechat(reason:String?){
        wechatSource=null;wechatPendingUntil=0;wechatAttempted=false
        if(task?.metadata?.platform=="wechat")stopRecording(reason==null,reason)
        scheduleHealth()
        if(authorized()&&recorder==null)status(waitingState())
    }
    private fun startRecording(source:String="phone",incoming:Boolean=true){
        if(recorder!=null||!authorized()||!permissions(this)||closing)return
        val callStarted=if(source=="phone"&&incoming)System.currentTimeMillis()else null
        callMono=SystemClock.elapsedRealtime();signal=false;recordingMono=0
        prefs.edit().putLong("last_attempt_at",System.currentTimeMillis()).putString("signal_observed",if(source=="phone")if(incoming)"phone_incoming"else"phone_outgoing"else source).apply()
        try{
            val audio=(if(Build.VERSION.SDK_INT>=31)MediaRecorder(this)else MediaRecorder()).also{recorder=it}
            val metadata=CallMetadata(platform=if(source=="phone")"phone"else"wechat",call_type=if(source=="wechat_video")"video"else"voice",destination=ServerConfig.baseUrl,recording_started_at=System.currentTimeMillis(),recording_ended_at=System.currentTimeMillis(),audio_duration_ms=0,
                call_started_at=callStarted,call_duration_estimated=true)
            val active=sessions.begin(device,metadata);task=active;activeId=active.id
            audio.setAudioSource(MediaRecorder.AudioSource.MIC)
            audio.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);audio.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            audio.setAudioChannels(1);audio.setAudioSamplingRate(24000);audio.setAudioEncodingBitRate(32000)
            audio.setMaxDuration(7_200_000);audio.setMaxFileSize(CallRecordingOutbox.MAX_AUDIO_BYTES)
            audio.setOutputFile(CallRecordingRuntime.outbox(this).audioFile(active.id).absolutePath)
            audio.setOnInfoListener{_,what,_->if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED||what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)worker.post{if(recorder===audio){stopRecording(false,"recording_limit");status("limit_reached")}}}
            audio.setOnErrorListener{_,_,_->worker.post{if(recorder===audio)restrict("recorder_error")}}
            if(!authorized()||!permissions(this))throw IllegalStateException()
            audio.prepare();audio.start();recordingMono=SystemClock.elapsedRealtime();signal=false
            scheduleHealth()
            status("recording_unverified");updateNotification("正在录音 · 双方声音未验证")
        }catch(_:Exception){restrict("microphone_unavailable")}
    }
    private val health=object:Runnable {
        override fun run(){
            if(closing)return
            if(!authorized()||!permissions(this@CallRecordingService)){stopRecording(false,"authorization_or_permission_changed");status("permissions_or_sync_required");stopSelf();return}
            try{
                if(!subscriptionsStable()){stopForSubscriptionChange();return}
                if(task?.metadata?.call_started_at==null&&recorder!=null&&!expandedAllowed()){
                    stopRecording(false,"authorization_or_permission_changed");status(waitingState())
                }
                checkWechat()
                recorder?.let{audio->
                    if(Build.VERSION.SDK_INT>=29&&audio.activeRecordingConfiguration?.isClientSilenced==true){restrict("system_silenced");return}
                    if(audio.maxAmplitude>0)signal=true
                    if(recordingMono>0&&!signal&&SystemClock.elapsedRealtime()-recordingMono>=30_000){restrict("suspected_silent");return}
                }
            }catch(_:Exception){restrict("recorder_error");return}
            scheduleHealth()
        }
    }
    private fun scheduleHealth(){
        worker.removeCallbacks(health)
        // 来电由系统回调立即处理；空闲只低频兜底，录音时及时检查静音与撤权。
        if(!closing)worker.postDelayed(health,if(recorder==null&&wechatPendingUntil==0L)30*60_000L else 1_000L)
    }
    private fun restrict(reason:String){
        stopRecording(false,reason)
        prefs.edit().remove("capability_blocked").putString("last_failure",reason).apply()
        status(reason);updateNotification("本次自录未成功 · 等待下一通");scheduleHealth()
    }
    private fun stopRecording(callEnded:Boolean,reason:String?){
        val audio=recorder?:return;recorder=null
        var failure=reason
        runCatching{if(audio.maxAmplitude>0)signal=true}
        try{audio.stop()}catch(_:Exception){if(failure==null)failure="recording_interrupted"}finally{runCatching{audio.release()}}
        if(failure==null&&!signal)failure="suspected_silent"
        if(failure!=null)prefs.edit().putString("last_failure",failure).apply()
        val active=task;task=null
        if(active!=null){
            val duration=if(recordingMono>0)(SystemClock.elapsedRealtime()-recordingMono).coerceIn(0,7_200_000)else 0
            val end=System.currentTimeMillis();val callDuration=if(callEnded&&active.metadata.call_started_at!=null)(SystemClock.elapsedRealtime()-callMono).coerceAtLeast(0)else null
            val metadata=active.metadata.copy(recording_ended_at=maxOf(end,active.metadata.recording_started_at+duration),audio_duration_ms=duration,
                call_ended_at=if(callDuration!=null)maxOf(end,(active.metadata.call_started_at?:end)+callDuration)else null,call_duration_ms=callDuration,
                recording_status=if(failure==null)"ended"else if(failure in listOf("system_silenced","microphone_unavailable","suspected_silent"))"restricted"else "interrupted",
                quality_status=if(failure in listOf("system_silenced","suspected_silent"))"suspected_silent"else "unverified",failure_reason=failure)
            try{sessions.finish(active,metadata){ImageUploadRuntime.isInputIdle()}}catch(_:Exception){status("local_finalize_pending")}
        }
        activeId=null;recordingMono=0
        CallRecordingJobService.wake(this)
        scheduleHealth()
        if(!closing)updateNotification(if(expandedAllowed())"等待电话 / 微信通话 · 收音待验证"else"等待普通来电 · 其他范围需确认授权")
    }
    private fun authorized()=CallRecordingRuntime.consent(this).recordingAllowed(device,ServerConfig.baseUrl)&&CollectionConsent.enabled(this)
    private fun status(code:String){prefs.edit().putString("state",code).apply()}
    private fun createChannel(){if(Build.VERSION.SDK_INT>=26)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"通话录音与待命",NotificationManager.IMPORTANCE_LOW))}
    private fun notification(text:String):Notification {
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val settings=PendingIntent.getActivity(this,NOTIFICATION_ID,Intent(this,CallRecordingSettingsActivity::class.java),flags)
        val stop=PendingIntent.getService(this,NOTIFICATION_ID,Intent(this,CallRecordingService::class.java).setAction(STOP),flags)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("通话录音（实验）").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(settings).addAction(0,"停止并撤回授权",stop).build()
    }
    private fun updateNotification(text:String){runCatching{(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID,notification(text))}}
    override fun onDestroy(){
        closing=true
        if(Build.VERSION.SDK_INT>=31)modeListener?.let{listener->runCatching{(getSystemService(AUDIO_SERVICE) as AudioManager).removeOnModeChangedListener(listener)}}
        watches.forEach{watch->
            watch.callback?.let{if(Build.VERSION.SDK_INT>=31)runCatching{watch.manager.unregisterTelephonyCallback(it)}}
            watch.legacy?.let{runCatching{watch.manager.listen(it,PhoneStateListener.LISTEN_NONE)}}
        }
        watches.clear()
        worker.removeCallbacksAndMessages(null)
        worker.post{
            try{stopRecording(false,"service_interrupted")}
            finally{
                // 与异步初始化串行关闭，覆盖主线程销毁时注册尚未完成的情况。
                wechatConnection?.close();wechatConnection=null
                activeId=null;isRunning=false;thread.quitSafely()
            }
        }
        stopForeground(true);super.onDestroy()
    }
    companion object {
        private const val CHANNEL="consented_call_audio_v1"
        private const val NOTIFICATION_ID=5175322
        private const val START="call_audio.START_FROM_ACTIVITY"
        private const val STOP="call_audio.STOP"
        private const val MANUAL_WECHAT="manual_wechat"
        private const val WECHAT_VIDEO="wechat_video"
        @Volatile var isRunning=false;private set
        @Volatile internal var activeId:String?=null;private set
        internal fun permissionBlock(context:Context):String? {
            if(listOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE).any{ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED})return "audio_phone_permissions_required"
            return null
        }
        internal fun permissions(context:Context)=permissionBlock(context)==null
        internal fun notificationSettingsIntent(context:Context):Intent {
            if(Build.VERSION.SDK_INT>=26){
                val channelBlocked=(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE
                return Intent(if(channelBlocked)android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,CHANNEL)
            }
            return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}"))
        }
        fun restoreFromActivity(activity:Activity){
            ServerConfig.init(activity)
            val prefs=CallRecordingRuntime.preferences(activity)
            if(isRunning||!CallRecordingRuntime.consent(activity).wantsRecording)return
            val blocked=permissionBlock(activity) ?: if(!CollectionConsent.enabled(activity))"permissions_or_sync_required" else null
            if(blocked!=null){prefs.edit().putString("state",blocked).apply();return}
            try{ContextCompat.startForegroundService(activity,Intent(activity,CallRecordingService::class.java).setAction(START))}
            catch(_:Exception){prefs.edit().putString("state","foreground_restricted").apply()}
        }
        fun startWechatFromActivity(activity:Activity,video:Boolean=false){
            ServerConfig.init(activity)
            val prefs=CallRecordingRuntime.preferences(activity)
            val consent=CallRecordingRuntime.consent(activity)
            if(!consent.wantsRecording||!consent.hasExpandedRecordingScope||!permissions(activity)||!CollectionConsent.enabled(activity)){
                prefs.edit().putString("state","permissions_or_sync_required").apply();return
            }
            if((activity.getSystemService(AUDIO_SERVICE) as AudioManager).mode!=AudioManager.MODE_IN_COMMUNICATION){
                prefs.edit().putString("state","wechat_call_not_active").apply();return
            }
            try{ContextCompat.startForegroundService(activity,Intent(activity,CallRecordingService::class.java).setAction(START).putExtra(MANUAL_WECHAT,true).putExtra(WECHAT_VIDEO,video))}
            catch(_:Exception){prefs.edit().putString("state","foreground_restricted").apply()}
        }
    }
}
