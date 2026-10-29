package com.babycry.caller

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import kotlin.math.sqrt

class ListenService : Service() {

    companion object {
        const val ACTION_START = "com.babycry.caller.START"
        const val ACTION_STOP = "com.babycry.caller.STOP"
        const val ACTION_RESUME = "com.babycry.caller.RESUME"
        @Volatile var alive = false

        private const val CHANNEL = "listen"
        private const val NOTIF_ID = 1
        private const val MODEL = "lite-model_yamnet_classification_tflite_1.tflite"

        // الاستماع
        private const val RMS_GATE = 0.02            // أقل من هذا الصوت لا نشغّل الموديل (توفير طاقة)
        private const val CRY_SCORE = 0.40f          // حساسية الكشف
        private const val HITS_NEEDED = 3            // عدد الإصابات المطلوبة
        private const val HIT_WINDOW_MS = 6000L      // خلال هذه المدة

        // الاتصال
        private const val REJECT_MAX_MS = 18000L     // مكالمة أقصر من هذا ومدتها صفر = رفض
        private const val REDIAL_DELAY_MS = 2000L
        private const val MAX_ATTEMPTS = 30          // حد أمان

        // الوضع المستمر
        private const val REST_MS = 15 * 60 * 1000L  // الراحة قبل إعادة الاستماع
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var listening = false
    private var calling = false
    private var attempts = 0
    private var failures = 0
    private var sawOffhook = false
    private var dialStart = 0L
    private var requestTime = 0L
    private var unregisterCallState: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "المراقبة", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll("تم الإيقاف يدوياً")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            if (!alive) { stopSelf(); return START_NOT_STICKY }
            if (!calling && !listening) {
                setStatus("يستمع")
                startListening()
            }
            return START_NOT_STICKY
        }
        startFg("يستمع")
        if (!alive) {
            alive = true
            Prefs.setLastStop(this, "")
            registerCallState()
            setStatus("يستمع")
            startListening()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alive = false
        listening = false
        cancelResume()
        handler.removeCallbacksAndMessages(null)
        unregisterCallState?.invoke()
        unregisterCallState = null
        super.onDestroy()
    }

    // ---------- إشعار ----------
    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("مراقب بكاء الطفل")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "إيقاف", stopPi
                ).build()
            )
            .build()
    }

    private fun startFg(text: String) {
        val n = buildNotif(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun setStatus(msg: String) {
        Prefs.setStatus(this, msg)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(msg))
    }

    private fun stopAll(msg: String) {
        alive = false
        listening = false
        calling = false
        cancelResume()
        handler.removeCallbacksAndMessages(null)
        unregisterCallState?.invoke()
        unregisterCallState = null
        Prefs.setLastStop(this, msg)
        Prefs.setStatus(this, "متوقف")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- الاستماع وكشف البكاء ----------
    private fun startListening() {
        if (listening) return
        listening = true
        Thread { listenLoop() }.start()
    }

    private fun listenLoop() {
        var classifier: AudioClassifier? = null
        var record: AudioRecord? = null
        var triggered = false
        try {
            val c = AudioClassifier.createFromFile(this, MODEL)
            classifier = c
            val sr = c.requiredTensorAudioFormat.sampleRate
            val tensor = c.createInputTensorAudio()
            val chunk = sr / 2   // نصف ثانية
            val minBuf = AudioRecord.getMinBufferSize(
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 2) * 2
            )
            record = r
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                handler.post { stopAll("تعذر فتح المايك") }
                return
            }
            r.startRecording()
            val buf = ShortArray(chunk)
            val hits = ArrayDeque<Long>()

            while (listening) {
                var got = 0
                while (got < chunk && listening) {
                    val n = r.read(buf, got, chunk - got)   // قراءة حاجزة: المعالج ينام أثناء الانتظار
                    if (n < 0) {
                        handler.post { stopAll("خطأ في قراءة المايك") }
                        return
                    }
                    got += n
                }
                if (!listening) break

                tensor.load(buf, 0, chunk)
                var sum = 0.0
                for (s in buf) { val f = s / 32768.0; sum += f * f }
                val rms = sqrt(sum / chunk)
                val now = SystemClock.elapsedRealtime()
                while (hits.isNotEmpty() && now - hits.first() > HIT_WINDOW_MS) hits.removeFirst()
                if (rms < RMS_GATE) continue

                val out = c.classify(tensor)
                val score = out.firstOrNull()?.categories
                    ?.firstOrNull { it.label.contains("baby cry", ignoreCase = true) }?.score ?: 0f
                if (score >= CRY_SCORE) hits.addLast(now)
                if (hits.size >= HITS_NEEDED) { triggered = true; break }
            }
        } catch (e: Exception) {
            handler.post { stopAll("خطأ: ${e.message}") }
            return
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            classifier?.close()
            listening = false
        }
        if (triggered) handler.post { if (alive) beginCalling() }
    }

    // ---------- الاتصال ----------
    private fun beginCalling() {
        calling = true
        attempts = 0
        failures = 0
        placeCall()
    }

    private val noDial = Runnable {
        if (!calling) return@Runnable
        failures++
        if (failures >= 2) {
            stopAll("تعذر إجراء الاتصال من الخلفية. فعّل خيار العرض فوق التطبيقات")
        } else {
            placeCall()
        }
    }

    private fun placeCall() {
        if (!calling) return
        attempts++
        if (attempts > MAX_ATTEMPTS) {
            stopAll("وصل الحد الأقصى من المحاولات ($MAX_ATTEMPTS)")
            return
        }
        sawOffhook = false
        dialStart = 0L
        requestTime = System.currentTimeMillis()
        setStatus("يتصل (محاولة $attempts)")
        val uri = Uri.fromParts("tel", Prefs.number(this), null)
        try {
            getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                stopAll("تعذر إجراء الاتصال: ${e2.message}")
                return
            }
        }
        handler.postDelayed(noDial, 25000)
    }

    @Suppress("DEPRECATION")
    private fun registerCallState() {
        val tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state)
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            unregisterCallState = { tm.unregisterTelephonyCallback(cb) }
        } else {
            val l = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state)
            }
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            unregisterCallState = { tm.listen(l, PhoneStateListener.LISTEN_NONE) }
        }
    }

    private fun handleState(state: Int) {
        if (!calling) return
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (!sawOffhook) {
                sawOffhook = true
                dialStart = SystemClock.elapsedRealtime()
                failures = 0
                handler.removeCallbacks(noDial)
            }
            TelephonyManager.CALL_STATE_IDLE -> if (sawOffhook) {
                sawOffhook = false
                val elapsed = SystemClock.elapsedRealtime() - dialStart
                handler.postDelayed({ evaluate(elapsed) }, 2500)   // ننتظر كتابة سجل المكالمات
            }
        }
    }

    private fun evaluate(elapsed: Long) {
        if (!calling) return
        val dur = lastOutgoingDuration()
        when {
            dur == null -> stopAll("انتهى الاتصال (تعذر قراءة سجل المكالمات)")
            dur > 0 -> stopAll("تم الرد على المكالمة")
            elapsed < REJECT_MAX_MS -> endCycle("تم رفض المكالمة")
            else -> handler.postDelayed({ placeCall() }, REDIAL_DELAY_MS)   // ما ردّ: نعيد الاتصال
        }
    }

    // بعد الرفض فقط: إذا الوضع المستمر مفعّل نرتاح 15 دقيقة ثم نرجع نستمع، وإلا نوقف كل شي
    private fun endCycle(msg: String) {
        if (!Prefs.persistent(this)) {
            stopAll(msg)
            return
        }
        calling = false
        handler.removeCallbacksAndMessages(null)
        setStatus("$msg — يعيد الاستماع بعد 15 دقيقة")
        val am = getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + REST_MS,
            resumePi()
        )
    }

    private fun resumePi(): PendingIntent = PendingIntent.getService(
        this, 3, Intent(this, ListenService::class.java).setAction(ACTION_RESUME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cancelResume() {
        try { getSystemService(AlarmManager::class.java).cancel(resumePi()) } catch (_: Exception) {}
    }

    private fun lastOutgoingDuration(): Long? = try {
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.DURATION),
            "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.DATE}>=?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), (requestTime - 5000).toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    } catch (e: Exception) {
        null
    }
}
