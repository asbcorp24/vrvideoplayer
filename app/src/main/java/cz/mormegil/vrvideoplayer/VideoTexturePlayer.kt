package cz.mormegil.vrvideoplayer

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaPlayer.OnVideoSizeChangedListener
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * RTP multicast H264 stream parameters.
 * Defaults match the LOW stream of the 360 server:
 * group 239.0.0.2, port 5006, RTP dynamic payload type 97.
 */
data class RtpMulticastConfig(
    val group: String = "239.0.0.2",
    val port: Int = 5006,
    val payloadType: Int = 97,
    val width: Int = 768,
    val height: Int = 384
)

class VideoTexturePlayer(
    private val context: Context,
    private val videoSourceUri: Uri?,
    private val rtpMulticastConfig: RtpMulticastConfig?,
    private val videoSizeChangedListener: OnVideoSizeChangedListener
) : OnFrameAvailableListener {
    companion object {
        private const val TAG = "VRVideoPlayerV"
        private const val RTP_HEADER_SIZE = 12
        private const val NAL_FU_A = 28
        private val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    private var surfaceTexture: SurfaceTexture? = null
    private var mediaPlayer: MediaPlayer? = null
    private var codec: MediaCodec? = null
    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var rtpThread: Thread? = null
    private var running = false

    private var videoPosition: Float = 0.0f
    private val frameAvailable: AtomicBoolean = AtomicBoolean(false)
    private var streamStartMs: Long = 0L

    private val fuBuffer = ArrayList<Byte>(128 * 1024)

    fun initializePlayback(texName: Int) {
        cleanup()

        val surfaceTexture = SurfaceTexture(texName)
        this.surfaceTexture = surfaceTexture
        surfaceTexture.setOnFrameAvailableListener(this)

        val surface = Surface(surfaceTexture)
        if (rtpMulticastConfig != null) {
            initializeRtpMulticastPlayback(surface, rtpMulticastConfig)
        } else {
            initializeLocalPlayback(surface)
        }
        surface.release()
    }

    private fun initializeLocalPlayback(surface: Surface) {
        val uri = videoSourceUri ?: throw IllegalArgumentException("Local playback requires videoSourceUri")

        val mediaPlayer = MediaPlayer()
        this.mediaPlayer = mediaPlayer
        mediaPlayer.setOnVideoSizeChangedListener(videoSizeChangedListener)
        mediaPlayer.setDataSource(context, uri)
        mediaPlayer.setSurface(surface)
        mediaPlayer.prepare()
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        mediaPlayer.start()

        Log.d(TAG, "Local VideoTexturePlayer initialized with $uri")
    }

    private fun initializeRtpMulticastPlayback(surface: Surface, config: RtpMulticastConfig) {
        Log.d(TAG, "Starting RTP multicast H264 receiver: ${config.group}:${config.port}, PT=${config.payloadType}")

        videoSizeChangedListener.onVideoSizeChanged(null, config.width, config.height)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        this.codec = codec
        codec.configure(format, surface, null, 0)
        codec.start()

        acquireMulticastLock()

        running = true
        streamStartMs = SystemClock.elapsedRealtime()
        rtpThread = thread(start = true, name = "RtpMulticastH264Receiver") {
            receiveRtpLoop(config)
        }
    }

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager
            ?.createMulticastLock("vrvideoplayer-rtp-multicast")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun receiveRtpLoop(config: RtpMulticastConfig) {
        val packetBuffer = ByteArray(64 * 1024)
        val groupAddress = InetAddress.getByName(config.group)
        var socket: MulticastSocket? = null

        try {
            socket = MulticastSocket(config.port).apply {
                reuseAddress = true
                soTimeout = 500
                receiveBufferSize = 4 * 1024 * 1024
            }
            multicastSocket = socket

            val iface = findWifiOrUsableInterface()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                socket.joinGroup(java.net.InetSocketAddress(groupAddress, config.port), iface)
            } else {
                @Suppress("DEPRECATION")
                socket.joinGroup(groupAddress)
            }

            Log.d(TAG, "Joined multicast group ${config.group}:${config.port} on interface ${iface?.displayName ?: "default"}")

            while (running) {
                val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }

                parseRtpPacket(packet.data, packet.length, config.payloadType)
                drainCodecOutput()
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTP multicast receiver stopped by error", e)
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val iface = findWifiOrUsableInterface()
                    socket?.leaveGroup(java.net.InetSocketAddress(groupAddress, config.port), iface)
                } else {
                    @Suppress("DEPRECATION")
                    socket?.leaveGroup(groupAddress)
                }
            } catch (_: Exception) {
            }
            socket?.close()
        }
    }

    private fun findWifiOrUsableInterface(): NetworkInterface? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                .firstOrNull { it.displayName.contains("wlan", ignoreCase = true) || it.name.contains("wlan", ignoreCase = true) }
                ?: NetworkInterface.getNetworkInterfaces().toList()
                    .firstOrNull { it.isUp && !it.isLoopback && it.supportsMulticast() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRtpPacket(data: ByteArray, length: Int, expectedPayloadType: Int) {
        if (length <= RTP_HEADER_SIZE) return

        val version = (data[0].toInt() ushr 6) and 0x03
        if (version != 2) return

        val csrcCount = data[0].toInt() and 0x0F
        val extension = (data[0].toInt() and 0x10) != 0
        val payloadType = data[1].toInt() and 0x7F
        if (payloadType != expectedPayloadType) return

        var offset = RTP_HEADER_SIZE + csrcCount * 4
        if (offset >= length) return

        if (extension) {
            if (offset + 4 > length) return
            val extLenWords = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4 + extLenWords * 4
            if (offset >= length) return
        }

        parseH264Payload(data, offset, length - offset)
    }

    private fun parseH264Payload(data: ByteArray, offset: Int, size: Int) {
        if (size <= 0) return

        val nalHeader = data[offset].toInt() and 0xFF
        val nalType = nalHeader and 0x1F

        when (nalType) {
            in 1..23 -> {
                val nal = ByteArray(ANNEX_B_START_CODE.size + size)
                System.arraycopy(ANNEX_B_START_CODE, 0, nal, 0, ANNEX_B_START_CODE.size)
                System.arraycopy(data, offset, nal, ANNEX_B_START_CODE.size, size)
                queueNal(nal)
            }

            NAL_FU_A -> parseFuA(data, offset, size)

            24 -> parseStapA(data, offset, size)

            else -> {
                // Unsupported H264 RTP packetization mode packet. Ignore it safely.
            }
        }
    }

    private fun parseFuA(data: ByteArray, offset: Int, size: Int) {
        if (size < 2) return

        val fuIndicator = data[offset].toInt() and 0xFF
        val fuHeader = data[offset + 1].toInt() and 0xFF
        val start = (fuHeader and 0x80) != 0
        val end = (fuHeader and 0x40) != 0
        val reconstructedNalHeader = ((fuIndicator and 0xE0) or (fuHeader and 0x1F)).toByte()

        val payloadOffset = offset + 2
        val payloadSize = size - 2

        if (start) {
            fuBuffer.clear()
            fuBuffer.addAll(ANNEX_B_START_CODE.toList())
            fuBuffer.add(reconstructedNalHeader)
        }

        if (fuBuffer.isEmpty()) return

        for (i in payloadOffset until payloadOffset + payloadSize) {
            fuBuffer.add(data[i])
        }

        if (end) {
            queueNal(fuBuffer.toByteArray())
            fuBuffer.clear()
        }
    }

    private fun parseStapA(data: ByteArray, offset: Int, size: Int) {
        var pos = offset + 1
        val end = offset + size
        while (pos + 2 <= end) {
            val nalSize = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            if (nalSize <= 0 || pos + nalSize > end) return

            val nal = ByteArray(ANNEX_B_START_CODE.size + nalSize)
            System.arraycopy(ANNEX_B_START_CODE, 0, nal, 0, ANNEX_B_START_CODE.size)
            System.arraycopy(data, pos, nal, ANNEX_B_START_CODE.size, nalSize)
            queueNal(nal)
            pos += nalSize
        }
    }

    private fun queueNal(nal: ByteArray) {
        val decoder = codec ?: return
        try {
            val inputIndex = decoder.dequeueInputBuffer(0)
            if (inputIndex < 0) {
                return
            }

            val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(nal)

            val ptsUs = (SystemClock.elapsedRealtime() - streamStartMs) * 1000L
            decoder.queueInputBuffer(inputIndex, 0, nal.size, ptsUs, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue H264 NAL to decoder", e)
        }
    }

    private fun drainCodecOutput() {
        val decoder = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = try {
                decoder.dequeueOutputBuffer(bufferInfo, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dequeue decoder output", e)
                return
            }

            when {
                outputIndex >= 0 -> decoder.releaseOutputBuffer(outputIndex, true)
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(TAG, "Decoder output format changed: ${decoder.outputFormat}")
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
    }

    fun rewind() {
        val mp = mediaPlayer ?: return
        mp.seekTo(0)
    }

    fun seek(relSeek: Int) {
        val mp = mediaPlayer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mp.seekTo(
                (mp.currentPosition + relSeek).toLong(),
                if (relSeek >= 0) MediaPlayer.SEEK_NEXT_SYNC else MediaPlayer.SEEK_PREVIOUS_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            mp.seekTo(mp.currentPosition + relSeek)
        }
    }

    fun getVideoPosition(): Float {
        return videoPosition
    }

    private fun cleanup() {
        running = false

        try {
            multicastSocket?.close()
        } catch (_: Exception) {
        }
        multicastSocket = null

        try {
            rtpThread?.join(1000)
        } catch (_: Exception) {
        }
        rtpThread = null

        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null

        val decoder = codec
        codec = null
        if (decoder != null) {
            try {
                decoder.stop()
            } catch (_: Exception) {
            }
            try {
                decoder.release()
            } catch (_: Exception) {
            }
        }

        val mediaPlayer = this.mediaPlayer
        this.mediaPlayer = null
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop()
            } catch (_: Exception) {
            }
            mediaPlayer.release()
        }

        val surfaceTexture = this.surfaceTexture
        this.surfaceTexture = null
        surfaceTexture?.release()

        fuBuffer.clear()
        Log.d(TAG, "VideoTexturePlayer cleaned up")
    }

    fun onPause() {
        mediaPlayer?.pause()
        Log.d(TAG, "onPause")
    }

    fun onResume() {
        mediaPlayer?.start()
        Log.d(TAG, "onResume")
    }

    fun onDestroy() {
        cleanup()
        Log.d(TAG, "onDestroy")
    }

    override fun onFrameAvailable(tex: SurfaceTexture?) {
        val mp = mediaPlayer
        if (mp != null) {
            val position = mp.currentPosition.toFloat()
            val duration = mp.duration.coerceAtLeast(1).toFloat()
            videoPosition = position / duration
        } else if (rtpMulticastConfig != null) {
            // Live stream: use a looping pseudo progress value only for the existing UI progress bar.
            val elapsed = (SystemClock.elapsedRealtime() - streamStartMs).coerceAtLeast(0L)
            videoPosition = (elapsed % 60_000L).toFloat() / 60_000f
        }

        frameAvailable.set(true)
    }

    fun updateIfNeeded() {
        if (frameAvailable.getAndSet(false)) {
            surfaceTexture?.updateTexImage()
        }
    }
}
