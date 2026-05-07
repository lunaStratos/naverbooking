package com.lunastratos.naverbookingphone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.lunastratos.naverbookingphone.MainActivity
import com.lunastratos.naverbookingphone.R
import com.lunastratos.naverbookingphone.data.Settings
import com.lunastratos.naverbookingphone.mail.MailConfig
import com.lunastratos.naverbookingphone.mail.MailSender
import com.lunastratos.naverbookingphone.scraper.BookingItem
import com.lunastratos.naverbookingphone.scraper.CheckResult
import com.lunastratos.naverbookingphone.scraper.NaverBookingScraper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var scraper: NaverBookingScraper? = null
    private val alertIdSeq = AtomicInteger(2000)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannelsIfNeeded()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(buildPersistentNotification("준비 중"))
        savePref(applicationContext, true)
        _isRunning.value = true
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _isRunning.value = false
        savePref(applicationContext, false)
        releaseWakeLock()
        scraper?.destroyOnMain()
        scraper = null
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NaverBooking::MonitorWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID_PERSISTENT, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID_PERSISTENT, notification)
        }
    }

    private fun buildPersistentNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT, immutable = true)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT, immutable = true)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
            .setContentTitle("네이버 예약 체크")
            .setContentText(text.lineSequence().firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .addAction(0, "중지", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updatePersistent(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID_PERSISTENT, buildPersistentNotification(text))
    }

    private fun fireAlert(newKeys: Set<String>) {
        val text = buildString {
            append("새로 예약 가능: ${newKeys.size}건\n")
            for (k in newKeys.take(15)) append("• ${k.replace("::", " · ")}\n")
            if (newKeys.size > 15) append("…외 ${newKeys.size - 15}건")
        }.trimEnd()
        val openPi = PendingIntent.getActivity(
            this, 100, Intent(this, MainActivity::class.java),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT, immutable = true)
        )
        val noti = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setContentTitle("예약 가능 알림")
            .setContentText("새로 예약 가능한 날짜 ${newKeys.size}건")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(alertIdSeq.incrementAndGet(), noti)
    }

    private suspend fun runLoop() {
        val settings = Settings(applicationContext)
        val s = NaverBookingScraper(applicationContext).also {
            it.init()
            scraper = it
        }
        var lastSeen: Set<String> = settings.lastSeenKeys
        val hadBaseline = lastSeen.isNotEmpty()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREAN)

        var iter = 0
        while (true) {
            iter++
            val placeId = settings.placeId
            val months = settings.monthsToCheck
            val intervalMs = settings.intervalSeconds.toLong() * 1000L
            val now = fmt.format(Date())

            val selected = settings.selectedItems
            if (selected.isEmpty()) {
                updatePersistent("$now — 선택된 상품이 없습니다.\n앱에서 상품을 선택해 주세요.")
                _lastSummary.value = "$now — 선택된 상품 없음"
                delay(intervalMs); continue
            }

            val targets = selected.map { BookingItem(it.title, it.url) }
            val results = mutableListOf<CheckResult>()
            for ((idx, item) in targets.withIndex()) {
                updatePersistent("$now — ${idx + 1}/${targets.size} ${item.title}")
                val res = runCatching { s.checkItem(item, months) }
                    .getOrElse { CheckResult(item, emptyList()) }
                results += res
            }

            val currentKeys = mutableSetOf<String>()
            val summary = StringBuilder("$now\n")
            for (r in results) {
                summary.append("• ${r.item.title}: ${r.totalAvailable}일\n")
                for (m in r.months) {
                    for (d in m.availableDays) {
                        currentKeys.add("$placeId::${r.item.title}::${m.title}::$d")
                    }
                }
            }
            val summaryText = summary.toString().trimEnd()
            updatePersistent(summaryText)
            _lastSummary.value = summaryText

            val newKeys = currentKeys - lastSeen
            // first-ever iteration with no saved baseline → suppress to avoid false alarm
            if (newKeys.isNotEmpty() && (hadBaseline || iter > 1)) {
                fireAlert(newKeys)
                if (settings.emailEnabled) {
                    sendAvailabilityMail(settings, results, newKeys)
                }
            }
            lastSeen = currentKeys
            settings.lastSeenKeys = currentKeys

            delay(intervalMs)
        }
    }

    private suspend fun sendAvailabilityMail(
        settings: Settings,
        results: List<CheckResult>,
        newKeys: Set<String>,
    ) {
        val cfg = MailConfig.fromSettings(settings)
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREAN).format(Date())
        val body = buildString {
            append("확인 시각: ").append(ts).append("\n")
            append("새로 예약 가능: ").append(newKeys.size).append("건\n\n")
            for (r in results) {
                if (r.totalAvailable == 0) continue
                append("▶ ").append(r.item.title).append("\n")
                append("  URL: ").append(r.item.url).append("\n")
                for (m in r.months) {
                    if (m.availableDays.isEmpty()) continue
                    append("  [").append(m.title).append("] ")
                        .append(m.availableDays.joinToString(", ")).append("\n")
                }
                append("\n")
            }
        }.trimEnd()
        runCatching { MailSender.send(cfg, "[네이버 예약] 예약 가능 알림", body) }
            .onFailure {
                _lastSummary.value = (_lastSummary.value + "\n메일 전송 실패: ${it.message ?: it.javaClass.simpleName}").trim()
            }
    }

    private fun createChannelsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID_MONITOR) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_MONITOR, "예약 체크 모니터링", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "백그라운드에서 예약 가능 여부를 주기적으로 확인합니다."
                    setShowBadge(false)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ID_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_ALERT, "예약 가능 알림", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "새로 예약 가능한 날짜가 발견되면 알립니다."
                    enableVibration(true)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID_MONITOR = "naver_booking_monitor"
        const val CHANNEL_ID_ALERT = "naver_booking_alert"
        const val NOTI_ID_PERSISTENT = 1001
        const val ACTION_STOP = "com.lunastratos.naverbookingphone.ACTION_STOP"
        private const val PREFS = "monitor_prefs"
        private const val KEY_AUTO_START = "auto_start"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _lastSummary = MutableStateFlow("")
        val lastSummary: StateFlow<String> = _lastSummary.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }

        fun shouldAutoStart(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, false)

        private fun savePref(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_START, value).apply()
        }

        private fun pendingIntentFlags(base: Int, immutable: Boolean): Int =
            if (immutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                base or PendingIntent.FLAG_IMMUTABLE
            } else {
                base
            }
    }
}
