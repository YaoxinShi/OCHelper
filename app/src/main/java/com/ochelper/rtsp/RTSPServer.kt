package com.ochelper.rtsp

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal RTSP/RTP server using Netty for RTSP signalling and UDP for RTP video packets.
 * Supports a single H.264 video stream via Camera2 + MediaCodec.
 */
class RTSPServer(
    private val context: Context,
    private val config: RTSPStreamConfig,
) {
    private val TAG = "RTSPServer"

    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup(2)
    private var serverChannel: ChannelFuture? = null

    // Camera + encoder state
    private val cameraThread = HandlerThread("RTSPCamera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private val running = AtomicBoolean(false)

    // SPS/PPS parameter sets (extracted from encoder output)
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null

    // Active RTSP sessions: sessionId -> RTP destination
    data class RtpDestination(val address: InetAddress, val rtpPort: Int, val sessionId: String)
    private val sessions = ConcurrentHashMap<String, RtpDestination>()
    private var rtpSocket: DatagramSocket? = null
    private val rtpSeq = AtomicInteger(0)
    private var rtpTimestamp = 0L

    fun start() {
        running.set(true)
        startEncoder()
        startRtspServer()
    }

    fun stop() {
        running.set(false)
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        encoder?.signalEndOfInputStream()
        encoder?.stop()
        encoder?.release()
        encoder = null
        encoderInputSurface?.release()
        encoderInputSurface = null
        cameraThread.quitSafely()
        rtpSocket?.close()
        rtpSocket = null
        sessions.clear()
        serverChannel?.channel()?.close()?.sync()
        bossGroup.shutdownGracefully()
        workerGroup.shutdownGracefully()
    }

    // ── RTSP Signalling ───────────────────────────────────────────────────────

    private fun startRtspServer() {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.SO_BACKLOG, 10)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(
                        LineBasedFrameDecoder(4096),
                        StringDecoder(),
                        StringEncoder(),
                        RtspHandler(),
                    )
                }
            })
        serverChannel = bootstrap.bind(config.port).sync()
        Log.i(TAG, "RTSP server listening on rtsp://0.0.0.0:${config.port}${config.streamPath}")
    }

    private inner class RtspHandler : SimpleChannelInboundHandler<String>() {
        private val requestLines = mutableListOf<String>()
        private var cseq = ""
        private var sessionId = ""
        private var clientRtpPort = 0

        override fun channelRead0(ctx: ChannelHandlerContext, line: String) {
            if (line.isBlank()) {
                // End of RTSP request
                if (requestLines.isNotEmpty()) processRequest(ctx)
                requestLines.clear()
            } else {
                requestLines.add(line)
                if (line.startsWith("CSeq:") || line.startsWith("CSeq: ")) {
                    cseq = line.substringAfter(":").trim()
                }
                if (line.startsWith("Session:")) {
                    sessionId = line.substringAfter(":").trim().split(";")[0].trim()
                }
                if (line.startsWith("Transport:") && line.contains("client_port=")) {
                    val ports = line.substringAfter("client_port=").trim().split("-")
                    clientRtpPort = ports[0].toIntOrNull() ?: 0
                }
            }
        }

        private fun processRequest(ctx: ChannelHandlerContext) {
            val firstLine = requestLines.firstOrNull() ?: return
            val method = firstLine.split(" ")[0]
            Log.d(TAG, "RTSP $method")

            val response = when (method) {
                "OPTIONS" -> rtspResponse(200, cseq, mapOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN"))
                "DESCRIBE" -> {
                    val sdp = buildSdp()
                    rtspResponse(200, cseq, mapOf(
                        "Content-Type" to "application/sdp",
                        "Content-Length" to sdp.length.toString()
                    )) + sdp
                }
                "SETUP" -> {
                    val sid = "oc${System.currentTimeMillis()}"
                    sessionId = sid
                    rtspResponse(200, cseq, mapOf(
                        "Session" to "$sid;timeout=60",
                        "Transport" to "RTP/AVP;unicast;client_port=$clientRtpPort-${clientRtpPort + 1};server_port=5004-5005"
                    ))
                }
                "PLAY" -> {
                    // Register this client for RTP delivery
                    val remoteAddr = ctx.channel().remoteAddress()
                    if (remoteAddr is java.net.InetSocketAddress && clientRtpPort > 0) {
                        sessions[sessionId] = RtpDestination(remoteAddr.address, clientRtpPort, sessionId)
                        Log.i(TAG, "RTSP PLAY: delivering to ${remoteAddr.address}:$clientRtpPort")
                    }
                    rtspResponse(200, cseq, mapOf(
                        "Session" to sessionId,
                        "Range" to "npt=0.000-"
                    ))
                }
                "TEARDOWN" -> {
                    sessions.remove(sessionId)
                    rtspResponse(200, cseq, mapOf("Session" to sessionId))
                }
                else -> rtspResponse(501, cseq, emptyMap())
            }
            ctx.writeAndFlush(response)
        }

        private fun rtspResponse(code: Int, cseqVal: String, headers: Map<String, String>): String {
            val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
            return buildString {
                append("RTSP/1.0 $code $reason\r\nCSeq: $cseqVal\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.w(TAG, "RTSP handler exception: ${cause.message}")
            ctx.close()
        }
    }

    private fun buildSdp(): String {
        val spsPps = buildString {
            val s = sps; val p = pps
            if (s != null && p != null) {
                val spsB64 = android.util.Base64.encodeToString(s, android.util.Base64.NO_WRAP)
                val ppsB64 = android.util.Base64.encodeToString(p, android.util.Base64.NO_WRAP)
                append("a=fmtp:96 packetization-mode=1;sprop-parameter-sets=$spsB64,$ppsB64\r\n")
            }
        }
        return buildString {
            append("v=0\r\n")
            append("o=- ${System.currentTimeMillis()} 1 IN IP4 0.0.0.0\r\n")
            append("s=OCHelper Live Stream\r\n")
            append("t=0 0\r\n")
            append("m=video 5004 RTP/AVP 96\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            append(spsPps)
            append("a=control:*\r\n")
        }
    }

    // ── Camera + H.264 Encoder ────────────────────────────────────────────────

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = codec.createInputSurface()
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val buffer = codec.getOutputBuffer(index) ?: return
                    if (info.size > 0 && running.get()) {
                        processEncoderOutput(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Encoder error: ${e.message}")
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    // Extract SPS/PPS from new format
                    sps = format.getByteBuffer("csd-0")?.let {
                        val bytes = ByteArray(it.remaining()); it.get(bytes); bytes
                    }
                    pps = format.getByteBuffer("csd-1")?.let {
                        val bytes = ByteArray(it.remaining()); it.get(bytes); bytes
                    }
                    Log.d(TAG, "Encoder format changed; sps=${sps?.size} pps=${pps?.size}")
                }
            }, cameraHandler)
            codec.start()
        }

        openCamera()
    }

    private fun openCamera() {
        val surface = encoderInputSurface ?: return
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val facing = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (config.cameraFacing == "front") facing == CameraCharacteristics.LENS_FACING_FRONT
            else facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull() ?: return

        try {
            cameraManager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    cameraDevice = camera
                    camera.createCaptureSession(listOf(surface), object :
                        android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            captureSession = session
                            val req = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                            }.build()
                            session.setRepeatingRequest(req, null, cameraHandler)
                            Log.i(TAG, "Camera streaming started")
                        }
                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                            Log.e(TAG, "Camera session configure failed")
                        }
                    }, cameraHandler)
                }
                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) { camera.close() }
                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    camera.close()
                    Log.e(TAG, "Camera error: $error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied")
        }
    }

    // ── RTP Packetization ─────────────────────────────────────────────────────

    private fun processEncoderOutput(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (sessions.isEmpty()) return
        if (rtpSocket == null) {
            rtpSocket = DatagramSocket()
        }
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data, 0, info.size)
        rtpTimestamp += 90000L / config.frameRate
        sendRtpH264(data, rtpTimestamp.toInt())
    }

    private fun sendRtpH264(nalData: ByteArray, timestamp: Int) {
        val socket = rtpSocket ?: return
        val maxPayload = 1400
        val ssrc = 0x12345678

        // Strip Annex-B start code if present
        val nal = if (nalData.size >= 4 &&
            nalData[0] == 0.toByte() && nalData[1] == 0.toByte() &&
            (nalData[2] == 0.toByte() && nalData[3] == 1.toByte() ||
             nalData[2] == 1.toByte())) {
            val skip = if (nalData[2] == 0.toByte()) 4 else 3
            nalData.copyOfRange(skip, nalData.size)
        } else nalData

        if (nal.size <= maxPayload) {
            // Single NAL unit packet
            val packet = buildRtpPacket(nal, timestamp, marker = true, ssrc = ssrc)
            sendToAll(socket, packet)
        } else {
            // FU-A fragmentation
            val nalHeader = nal[0]
            val nalType = (nalHeader.toInt() and 0x1F).toByte()
            val fuIndicator = ((nalHeader.toInt() and 0xE0) or 28).toByte()
            var offset = 1
            while (offset < nal.size) {
                val isFirst = offset == 1
                val remaining = nal.size - offset
                val chunkSize = minOf(remaining, maxPayload - 2)
                val isLast = (offset + chunkSize) >= nal.size

                val fuHeader = ((if (isFirst) 0x80 else 0) or
                        (if (isLast) 0x40 else 0) or
                        (nalType.toInt() and 0x1F)).toByte()
                val payload = ByteArray(2 + chunkSize)
                payload[0] = fuIndicator
                payload[1] = fuHeader
                System.arraycopy(nal, offset, payload, 2, chunkSize)

                val packet = buildRtpPacket(payload, timestamp, marker = isLast, ssrc = ssrc)
                sendToAll(socket, packet)
                offset += chunkSize
            }
        }
    }

    private fun buildRtpPacket(payload: ByteArray, timestamp: Int, marker: Boolean, ssrc: Int): ByteArray {
        val seq = rtpSeq.getAndIncrement() and 0xFFFF
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = ((if (marker) 0x80 else 0) or 96).toByte()
        header[2] = (seq shr 8).toByte()
        header[3] = (seq and 0xFF).toByte()
        header[4] = (timestamp shr 24).toByte()
        header[5] = (timestamp shr 16).toByte()
        header[6] = (timestamp shr 8).toByte()
        header[7] = (timestamp and 0xFF).toByte()
        header[8] = (ssrc shr 24).toByte()
        header[9] = (ssrc shr 16).toByte()
        header[10] = (ssrc shr 8).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header + payload
    }

    private fun sendToAll(socket: DatagramSocket, data: ByteArray) {
        sessions.values.forEach { dest ->
            try {
                socket.send(DatagramPacket(data, data.size, dest.address, dest.rtpPort))
            } catch (_: Exception) {}
        }
    }
}
