package com.example.multiroomspeaker

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs on the phone that owns the audio (the "Host" / master).
 *
 * - Decodes the chosen file to raw PCM once.
 * - Advertises itself on the local network via NSD.
 * - Accepts client connections, performs a clock-sync handshake with each,
 *   then live-broadcasts the audio to every connected client, timestamped
 *   so they all play it back at (approximately) the same instant.
 * - Also plays the audio on its own speaker so the host itself is part of the group.
 */
class HostAudioService : Service() {

    companion object {
        private const val TAG = "HostAudioService"
        const val CHANNEL_ID = "host_audio_channel"
        const val NOTIF_ID = 1
        var onLog: ((String) -> Unit)? = null
    }

    inner class LocalBinder : Binder() {
        fun getService(): HostAudioService = this@HostAudioService
    }

    private val binder = LocalBinder()
    private val nsdHelper by lazy { NsdHelper(this) }
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<ClientHandler>()

    private var decoded: DecodedAudio? = null
    private var localTrack: AudioTrack? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun start(uri: Uri) {
        if (running.get()) return
        running.set(true)
        startForeground(NOTIF_ID, buildNotification("جاري البث..."))

        Thread {
            try {
                log("جاري تحويل الصوت...")
                val audio = AudioDecoder.decode(this, uri)
                decoded = audio
                log("تم فك الصوت: ${audio.sampleRate}Hz ${audio.channelCount}ch, ${audio.pcm.size / 1024}KB")

                serverSocket = ServerSocket(Protocol.PORT)
                nsdHelper.registerService(Protocol.PORT)
                log("تم فتح الخادم على المنفذ ${Protocol.PORT}")

                acceptLoop()
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                log("خطأ: ${e.message}")
            }
        }.start()

        Thread {
            // Give the server a brief moment to come up before broadcasting starts.
            Thread.sleep(300)
            broadcastLoop()
        }.start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        listeners.forEach { it.sendStopAndClose() }
        listeners.clear()
        nsdHelper.unregisterService()
        localTrack?.stop()
        localTrack?.release()
        localTrack = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (running.get()) {
            try {
                val client = socket.accept()
                log("عميل جديد اتصل: ${client.inetAddress.hostAddress}")
                val handler = ClientHandler(client) { listeners.remove(it) }
                listeners.add(handler)
                handler.start()
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "accept failed", e)
                break
            }
        }
    }

    /** Reads the decoded PCM in real time and fans it out to the host speaker + all clients. */
    private fun broadcastLoop() {
        while (running.get() && decoded == null) {
            Thread.sleep(50)
        }
        val audio = decoded ?: return

        val bytesPerSecond = audio.sampleRate * audio.channelCount * 2
        val channelConfig = if (audio.channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(audio.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

        localTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        localTrack?.play()

        val sessionStart = System.nanoTime()
        var position = 0
        log("بدء البث")

        while (running.get() && position < audio.pcm.size) {
            val chunkLen = minOf(Protocol.CHUNK_SIZE_BYTES, audio.pcm.size - position)
            val chunk = audio.pcm.copyOfRange(position, position + chunkLen)

            val elapsedAudioNanos = (position.toDouble() / bytesPerSecond * 1_000_000_000L).toLong()
            val targetHostTime = sessionStart + elapsedAudioNanos + Protocol.PLAYOUT_DELAY_NANOS

            localTrack?.write(chunk, 0, chunk.size)

            for (listener in listeners) {
                listener.enqueue(targetHostTime, chunk)
            }

            position += chunkLen

            val expectedWallTime = sessionStart + elapsedAudioNanos
            val now = System.nanoTime()
            val sleepNanos = expectedWallTime - now
            if (sleepNanos > 0) {
                val ms = sleepNanos / 1_000_000
                val ns = (sleepNanos % 1_000_000).toInt()
                try { Thread.sleep(ms, ns) } catch (_: InterruptedException) {}
            }
        }

        log("انتهى البث")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Host Audio", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Multi Room Speaker - Host")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    /** Handles one connected client: clock sync handshake, then forwards queued audio chunks. */
    private inner class ClientHandler(
        private val socket: Socket,
        private val onDone: (ClientHandler) -> Unit
    ) : Thread() {

        private val queue = LinkedBlockingQueue<Pair<Long, ByteArray>>(200)
        private var writerThread: Thread? = null
        private val alive = AtomicBoolean(true)

        override fun run() {
            try {
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                // 1) Clock sync: respond to a fixed number of sync requests from the client.
                repeat(Protocol.SYNC_SAMPLES) {
                    val type = input.readByte()
                    if (type == Protocol.MSG_SYNC_REQUEST) {
                        val t1 = input.readLong()
                        synchronized(output) {
                            output.writeByte(Protocol.MSG_SYNC_RESPONSE.toInt())
                            output.writeLong(t1)
                            output.writeLong(System.nanoTime())
                            output.flush()
                        }
                    }
                }

                // 2) Send the audio format header once decoding info is available.
                while (decoded == null && running.get()) sleep(50)
                val audio = decoded ?: return

                synchronized(output) {
                    output.writeByte(Protocol.MSG_AUDIO_HEADER.toInt())
                    output.writeInt(audio.sampleRate)
                    output.writeInt(audio.channelCount)
                    output.writeInt(16)
                    output.flush()
                }

                // 3) Drain the outgoing queue to this client as chunks are enqueued live.
                writerThread = Thread {
                    try {
                        while (alive.get()) {
                            val (targetTime, chunk) = queue.take()
                            synchronized(output) {
                                output.writeByte(Protocol.MSG_AUDIO_CHUNK.toInt())
                                output.writeLong(targetTime)
                                output.writeInt(chunk.size)
                                output.write(chunk)
                                output.flush()
                            }
                        }
                    } catch (_: Exception) {
                        // client disconnected or stopped; fall through to cleanup
                    }
                }.also { it.start() }

                writerThread?.join()
            } catch (e: Exception) {
                Log.w(TAG, "ClientHandler ended: ${e.message}")
            } finally {
                closeQuietly()
                onDone(this)
            }
        }

        fun enqueue(targetHostTime: Long, chunk: ByteArray) {
            if (!queue.offer(targetHostTime to chunk)) {
                // Client is falling behind; drop the oldest chunk to make room rather
                // than letting memory grow unbounded or blocking the broadcast loop.
                queue.poll()
                queue.offer(targetHostTime to chunk)
            }
        }

        fun sendStopAndClose() {
            alive.set(false)
            try {
                val output = DataOutputStream(socket.getOutputStream())
                synchronized(output) {
                    output.writeByte(Protocol.MSG_STOP.toInt())
                    output.flush()
                }
            } catch (_: Exception) {}
            closeQuietly()
        }

        private fun closeQuietly() {
            alive.set(false)
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
