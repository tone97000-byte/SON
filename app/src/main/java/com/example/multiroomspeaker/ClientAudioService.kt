package com.example.multiroomspeaker

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs on every phone that should just "hear" the host's audio.
 *
 * Connects to the Host, estimates the clock offset between the two devices,
 * then plays each incoming PCM chunk at the moment the host intended
 * (converted into this device's own clock), so playback lines up across phones.
 */
class ClientAudioService : Service() {

    companion object {
        private const val TAG = "ClientAudioService"
        const val CHANNEL_ID = "client_audio_channel"
        const val NOTIF_ID = 2
        var onLog: ((String) -> Unit)? = null
    }

    inner class LocalBinder : Binder() {
        fun getService(): ClientAudioService = this@ClientAudioService
    }

    private val binder = LocalBinder()
    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var track: AudioTrack? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun connectTo(host: String, port: Int) {
        if (running.get()) return
        running.set(true)
        startForeground(NOTIF_ID, buildNotification("جاري الاتصال بـ $host..."))

        Thread {
            try {
                runClient(host, port)
            } catch (e: Exception) {
                Log.e(TAG, "client failed", e)
                log("خطأ: ${e.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        track?.stop()
        track?.release()
        track = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runClient(host: String, port: Int) {
        val sock = Socket(host, port)
        socket = sock
        val input = DataInputStream(sock.getInputStream())
        val output = DataOutputStream(sock.getOutputStream())

        log("متصل. جاري مزامنة الساعة...")
        val offsetNanos = syncClock(input, output)
        log("تمت المزامنة. الفارق التقريبي: ${offsetNanos / 1_000_000}ms")

        // Read the audio format header.
        val type = input.readByte()
        require(type == Protocol.MSG_AUDIO_HEADER) { "Expected header, got $type" }
        val sampleRate = input.readInt()
        val channelCount = input.readInt()
        input.readInt() // bitsPerSample, currently always 16

        val channelConfig = if (channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()

        log("جاري الاستقبال والتشغيل...")

        while (running.get()) {
            val msgType = input.readByte()
            when (msgType) {
                Protocol.MSG_AUDIO_CHUNK -> {
                    val targetHostTime = input.readLong()
                    val length = input.readInt()
                    val payload = ByteArray(length)
                    input.readFully(payload)

                    // Convert the host's intended play time into our own clock, then
                    // wait until that instant before handing the samples to AudioTrack.
                    val localTarget = targetHostTime - offsetNanos
                    val waitNanos = localTarget - System.nanoTime()
                    if (waitNanos > 0) {
                        val ms = waitNanos / 1_000_000
                        val ns = (waitNanos % 1_000_000).toInt()
                        try { Thread.sleep(ms, ns) } catch (_: InterruptedException) {}
                    }
                    track?.write(payload, 0, payload.size)
                }
                Protocol.MSG_STOP -> {
                    log("الخادم أوقف البث")
                    running.set(false)
                }
                else -> { /* ignore unknown */ }
            }
        }
    }

    /**
     * Simple NTP-style offset estimate: for each round trip, T1 = our send time,
     * Thost = host's clock when it received/replied, T4 = our receive time.
     * offset ≈ Thost - (T1+T4)/2, averaged over several samples to smooth jitter.
     * Returned value satisfies: hostTime ≈ localTime + offset.
     */
    private fun syncClock(input: DataInputStream, output: DataOutputStream): Long {
        var offsetSum = 0L
        repeat(Protocol.SYNC_SAMPLES) {
            val t1 = System.nanoTime()
            synchronized(output) {
                output.writeByte(Protocol.MSG_SYNC_REQUEST.toInt())
                output.writeLong(t1)
                output.flush()
            }
            val type = input.readByte()
            if (type == Protocol.MSG_SYNC_RESPONSE) {
                input.readLong() // echoed t1, unused here
                val hostTime = input.readLong()
                val t4 = System.nanoTime()
                val offset = hostTime - (t1 + t4) / 2
                offsetSum += offset
            }
        }
        return offsetSum / Protocol.SYNC_SAMPLES
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Client Audio", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Multi Room Speaker - Client")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}
