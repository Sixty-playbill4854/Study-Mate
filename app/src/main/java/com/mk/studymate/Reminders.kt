package com.mk.studymate

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Reminders {
    private const val CHANNEL = "study_reminders"
    private const val EXTRA_ID = "plan_id"
    private const val ACTION = "com.mk.studymate.REMIND"

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(c).canScheduleExactAlarms()

    fun ensureChannel(c: Context) {
        val ch = NotificationChannel(CHANNEL, "Study reminders", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Reminders for your scheduled study plans"
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    // One PendingIntent per plan id (same action + data + request code) => no duplicate alarms.
    private fun pending(c: Context, id: Long, flag: Int): PendingIntent? {
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction(ACTION)
            .setData(Uri.parse("mkstudymate://plan/$id"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(c, id.toInt(), i, flag or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelAlarm(c: Context, id: Long) {
        pending(c, id, PendingIntent.FLAG_NO_CREATE)?.let { am(c).cancel(it); it.cancel() }
    }

    /** Cancels the alarm and dismisses any visible notification. */
    fun cancel(c: Context, id: Long) {
        cancelAlarm(c, id)
        NotificationManagerCompat.from(c).cancel(id.toInt())
    }

    /** Idempotent: replaces any existing alarm. Skips completed or past plans. */
    fun schedule(c: Context, p: Plan) {
        cancelAlarm(c, p.id)
        if (p.completed) return
        val at = p.startMillis()
        if (at <= System.currentTimeMillis()) return
        val pi = pending(c, p.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (canExact(c)) am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // inexact fallback
        } catch (e: SecurityException) {
            am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    suspend fun rescheduleAll(c: Context) {
        Db.get(c).dao().pending().forEach { schedule(c, it) }
    }

    fun show(c: Context, p: Plan) {
        ensureChannel(c)
        val open = PendingIntent.getActivity(
            c, p.id.toInt(),
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (p.notes.isBlank()) "Time to study!" else p.notes
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${p.subject}: ${p.task}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(p.id.toInt(), n)
        } catch (_: SecurityException) { /* notification permission denied */ }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("plan_id", -1L)
        if (id < 0) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val p = Db.get(c).dao().get(id)
                if (p != null && !p.completed) Reminders.show(c, p)
            } finally { result.finish() }
        }
    }
}

/** Boot, app update, time/zone change, exact-alarm permission change => re-register all alarms. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Reminders.rescheduleAll(c) } finally { result.finish() }
        }
    }
}
