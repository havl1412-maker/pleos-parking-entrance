package com.lastmeter.parking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 앱 화면이 내려가 내비만 보일 때도 위치를 계속 받기 위한 포그라운드 서비스.
 * 이게 없으면 화면에서 내려간 앱은 시스템이 프로세스를 멈춰서(frozen) 입구 접근을 알아챌 수 없다.
 * 안내 대상(목적지)이 정해져 있는 동안에만 켠다.
 */
class GuidanceKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "주차장 입구 안내", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("주차장 입구 안내 대기 중")
            .setContentText("목적지 근처에 오면 입구를 알려 드려요")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "guidance"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GuidanceKeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuidanceKeepAliveService::class.java))
        }
    }
}
