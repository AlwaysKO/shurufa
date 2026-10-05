package com.yuyan.imemodule.data.collect

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.ui.activity.SettingsActivity

/** User choice survives process death; lifecycle cleanup is not an explicit opt-out. */
class BalancedLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val policy = BalancedLocationPolicy()
    private lateinit var manager: LocationManager
    private lateinit var preferences: SharedPreferences
    private var started = false
    private var activeListener: LocationListener? = null
    private var passiveListener: LocationListener? = null
    private val confirmationTimeout = Runnable {
        if (started) {
            if (!allowed()) finishSession()
            else {
                val oldInterval = policy.intervalMs
                policy.endConfirmation()
                if (oldInterval != policy.intervalMs) registerUpdates()
            }
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (started && !allowed()) finishSession()
    }
    private val checkPermission = object : Runnable {
        override fun run() {
            if (!allowed()) finishSession()
            else if (started) handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            preferences.edit().putBoolean(KEY, false).commit()
        }
        if (!allowed()) {
            finishSession()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        try {
            showNotification()
            started = true
            isRunning = true
            DataCollector.setBalancedLocationOwner(true)
            registerUpdates()
            if (started) handler.post(checkPermission)
        } catch (_: Exception) {
            finishSession()
        }
        return if (started) START_STICKY else START_NOT_STICKY
    }

    private fun allowed(): Boolean = preferences.getBoolean(KEY, false) &&
        CollectionConsent.enabled(this) && preferences.getBoolean("location_tracking_enable", true) &&
        LocationPermissions.hasForegroundPermission(this) && LocationManagerCompat.isLocationEnabled(manager)

    private fun receive(location: Location) {
        if (!started || !allowed()) { finishSession(); return }
        val oldInterval = policy.intervalMs
        policy.observe(System.currentTimeMillis(), LocationCandidate(location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY, location.time,
            location.provider, location.elapsedRealtimeNanos),
            LocationSpeedQuality.from(location).speedMps, SystemClock.elapsedRealtime())
        DataCollector.reportBalancedLocation(this, location, policy.intervalMs)
        if (oldInterval != policy.intervalMs) registerUpdates()
        handler.removeCallbacks(confirmationTimeout)
        if (started) policy.confirmationDelayMs(SystemClock.elapsedRealtime())?.let {
            handler.postDelayed(confirmationTimeout, it)
        }
    }

    @Suppress("MissingPermission")
    private fun registerUpdates() {
        removeUpdates()
        if (!started || !allowed()) return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = receive(location)
            @Deprecated("Required by Android 6–9 LocationListener")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) { if (started && !allowed()) finishSession() }
        }
        activeListener = listener
        // During stays the OS receives genuinely lower-frequency, zero-distance requests,
        // detecting renewed motion in ~5 minutes; unchanged fixes never enter the upload queue.
        var registered = false
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            try {
                manager.requestLocationUpdates(provider, policy.intervalMs, 0f, listener, Looper.getMainLooper())
                registered = true
            } catch (_: Exception) { /* Coarse-only permission or absent provider: use the other provider. */ }
        }
        if (!registered) { finishSession(); return }
        val passive = object : LocationListener {
            override fun onLocationChanged(location: Location) = receive(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Required by Android 6–9 LocationListener")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        try {
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 30_000, 0f, passive, Looper.getMainLooper())
            passiveListener = passive
        } catch (_: Exception) { /* Active low-frequency requests remain. */ }
    }

    private fun removeUpdates() {
        activeListener?.let { runCatching { manager.removeUpdates(it) } }
        passiveListener?.let { runCatching { manager.removeUpdates(it) } }
        activeListener = null
        passiveListener = null
    }

    private fun finishSession() {
        val owned = started
        started = false
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        removeUpdates()
        if (owned) DataCollector.setBalancedLocationOwner(false)
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        finishSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showNotification() {
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "均衡位置记录", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, BalancedLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("均衡位置记录已开启")
            .setContentText("仅位置变化时上报；点击停止可结束记录")
            .setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止记录", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY = "balanced_location_enabled"
        const val ACTION_STOP = "com.yuyan.imemodule.STOP_BALANCED_LOCATION"
        private const val CHANNEL = "balanced_location"
        private const val NOTIFICATION_ID = 2107
        @Volatile var isRunning = false
            private set

        fun startFromActivity(activity: Activity): Boolean {
            if (activity.isFinishing || activity.isDestroyed || !CollectionConsent.enabled(activity) ||
                !LocationPermissions.hasForegroundPermission(activity)) return false
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            if (!prefs.getBoolean("location_tracking_enable", true)) return false
            val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!LocationManagerCompat.isLocationEnabled(manager)) return false
            return try {
                DataCollector.init(activity)
                prefs.edit().putBoolean(KEY, true).commit()
                ContextCompat.startForegroundService(activity, Intent(activity, BalancedLocationService::class.java))
                true
            } catch (_: Exception) {
                false
            }
        }

        fun isEnabled(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)

        /** No permission UI, alarms or forced resurrection. Android may reject a background start. */
        fun restore(context: Context): Boolean = restoreIfAllowed(context, visibleActivity = false)

        fun restoreFromActivity(activity: Activity): Boolean {
            if (activity.isFinishing || activity.isDestroyed) return false
            return restoreIfAllowed(activity, visibleActivity = true)
        }

        private fun restoreIfAllowed(context: Context, visibleActivity: Boolean): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!isEnabled(context) || !CollectionConsent.enabled(context) ||
                !prefs.getBoolean("location_tracking_enable", true) ||
                !LocationPermissions.hasForegroundPermission(context)) return false
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!LocationManagerCompat.isLocationEnabled(manager)) return false
            if (isRunning) return true
            if (!visibleActivity && !LocationPermissions.hasBackgroundPermission(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, BalancedLocationService::class.java))
                true
            } catch (_: RuntimeException) {
                // Keep the user's intent; retry at a later eligible lifecycle entry.
                false
            }
        }

        fun stop(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, false).commit()
            context.stopService(Intent(context, BalancedLocationService::class.java))
        }
    }
}
