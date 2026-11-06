package com.logman.muzi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Keeps Muzi alive while music plays with the screen off / app in background. */
class PlaybackService : Service() {
    companion object {
        const val CHANNEL = "muzi_playback"
        const val NOTIF_ID = 11
        const val ACTION_UPDATE = "com.logman.muzi.UPDATE"
        const val CMD_TOGGLE = "com.logman.muzi.CMD_TOGGLE"
        const val CMD_NEXT = "com.logman.muzi.CMD_NEXT"
        const val CMD_PREV = "com.logman.muzi.CMD_PREV"
        @Volatile var running = false
    }

    private var title = "Muzi Music"
    private var artist = ""
    private var playing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CMD_TOGGLE -> MainActivity.current?.runCommand("toggle")
                CMD_NEXT -> MainActivity.current?.runCommand("next")
                CMD_PREV -> MainActivity.current?.runCommand("prev")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val f = IntentFilter().apply { addAction(CMD_TOGGLE); addAction(CMD_NEXT); addAction(CMD_PREV) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE) {
            title = intent.getStringExtra("title")?.ifBlank { null } ?: "Muzi Music"
            artist = intent.getStringExtra("artist") ?: ""
            playing = intent.getBooleanExtra("playing", false)
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun pi(action: String, code: Int): PendingIntent {
        val i = Intent(action).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(this, code, i, flags)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(artist.ifBlank { "Muzi Music" })
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", pi(CMD_PREV, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", pi(CMD_TOGGLE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", pi(CMD_NEXT, 3))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        val n = buildNotification()
        if (playing) {
            try {
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NOTIF_ID, n)
                foreground = true
            } catch (_: Exception) { }
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muzi:playback")
            }
            try { wakeLock?.acquire(6 * 60 * 60 * 1000L) } catch (_: Exception) { }
        } else {
            releaseLock()
            if (foreground) {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH) else stopForeground(false)
                foreground = false
            }
            try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n) } catch (_: Exception) { }
        }
    }

    private fun releaseLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        releaseLock()
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        stopForeground(true)
        super.onDestroy()
    }
}
