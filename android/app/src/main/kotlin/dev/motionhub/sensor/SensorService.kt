package dev.motionhub.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService

/**
 * Foreground service that owns the [SensorEngine], so tracking keeps running when the user leaves
 * the app or the screen turns off (no display, no preview: less heat and battery).
 *
 * Android only allows background camera use from a foreground service of type "camera" that was
 * started while the app was visible, with a permanent notification — that is what this is.
 */
@ExperimentalCamera2Interop
class SensorService : LifecycleService() {

    inner class LocalBinder : Binder() { val engine: SensorEngine get() = this@SensorService.engine }

    lateinit var engine: SensorEngine; private set
    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        // Keep the CPU running with the screen off; the camera stream alone does not guarantee it.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motionhub:sensor").apply { acquire() }
        engine = SensorEngine(this, this)
        engine.start()
        main.post(object : Runnable {
            override fun run() {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(engine.statusLine()))
                main.postDelayed(this, 5000)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        engine.stop()
        wakeLock?.release()
        super.onDestroy()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Motion sensor", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the phone is tracking for the TV"
        })
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SensorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("MotionHub sensor is tracking")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "dev.motionhub.sensor.STOP"
        const val ACTION_STOPPED = "dev.motionhub.sensor.STOPPED"
        private const val CHANNEL = "sensor"
        private const val NOTIFICATION_ID = 1
    }
}
