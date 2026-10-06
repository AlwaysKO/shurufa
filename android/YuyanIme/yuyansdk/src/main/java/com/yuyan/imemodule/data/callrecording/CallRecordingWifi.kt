package com.yuyan.imemodule.data.callrecording

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 扫描与网络无关；仅有效 Wi-Fi 可上传，套接字和 DNS 固定到本次批次的网络。 */
internal object CallRecordingWifi {
    private var manager:ConnectivityManager?=null
    private var callback:ConnectivityManager.NetworkCallback?=null
    private var readyNetwork:Network?=null
    fun network(context:Context):Network?=runCatching {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val active=cm.activeNetwork ?: return null
        val caps=cm.getNetworkCapabilities(active) ?: return null
        active.takeIf{caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}
    }.getOrNull()
    fun client(network:Network):OkHttpClient=OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(object:okhttp3.Dns{override fun lookup(hostname:String)=network.getAllByName(hostname).toList()})
        .connectionPool(ConnectionPool(0,1,TimeUnit.SECONDS))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS)
        .callTimeout(180,TimeUnit.SECONDS).build()

    @Synchronized fun observe(context:Context){
        val app=context.applicationContext
        if(!CallRecordingRuntime.consent(app).wantsUpload)return
        val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if(manager===cm && callback!=null)return
        stop()
        val listener=object:ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:Network){changed(app,this)}
            override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities){changed(app,this)}
            override fun onLost(network:Network){synchronized(this@CallRecordingWifi){
                if(callback===this && readyNetwork==network)readyNetwork=null
            }}
        }
        manager=cm;callback=listener
        try{cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),listener)}
        catch(_:Exception){manager=null;callback=null;readyNetwork=null} // 周期扫描仍保留30分钟兜底。
    }
    @Synchronized private fun changed(context:Context,source:ConnectivityManager.NetworkCallback){
        if(callback!==source)return
        if(!CallRecordingRuntime.consent(context).wantsUpload){stop();return}
        val ready=network(context)
        val becameReady=ready!=null && ready!=readyNetwork
        readyNetwork=ready
        if(becameReady)CallRecordingJobService.wake(context)
    }
    @Synchronized fun stop(){
        val cm=manager;val listener=callback
        manager=null;callback=null;readyNetwork=null
        if(cm!=null && listener!=null)runCatching{cm.unregisterNetworkCallback(listener)}
    }
}
