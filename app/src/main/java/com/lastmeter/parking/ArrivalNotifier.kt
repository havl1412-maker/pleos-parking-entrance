package com.lastmeter.parking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 앱 화면이 내려가 있을 때 입구 300m 전에 띄우는 알림. 누르면 앱이 내비 옆 칸에 열린다.
 * 음성 안내는 알림과 상관없이 나간다.
 */
object ArrivalNotifier {
    private const val CHANNEL_ID = "arrival"
    private const val NOTIFICATION_ID = 2

    fun show(context: Context, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "입구 접근 알림", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
