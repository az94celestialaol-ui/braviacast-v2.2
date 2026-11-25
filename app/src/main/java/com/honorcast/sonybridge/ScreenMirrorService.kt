package com.honorcast.sonybridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScreenMirrorService : Service() {

    companion object {
        const val ACTION_START_MIRROR = "ACTION_START_MIRROR"
        const val ACTION_START_MEDIA_CAST = "ACTION_START_MEDIA_CAST"
        const val ACTION_UPDATE_SETTINGS = "ACTION_UPDATE_SETTINGS"
        const val ACTION_REMOTE_COMMAND = "ACTION_REMOTE_COMMAND"
        const val ACTION_STOP = "ACTION_STOP_MIRROR"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PORT = "port"
        const val EXTRA_LONG_EDGE = "long_edge"
        const val EXTRA_FPS = "fps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_CAPTURE_AUDIO = "capture_audio"
        const val EXTRA_USE_RELAY = "use_relay"
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_RELAY_URL = "relay_url"
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MEDIA_URL = "media_url"
        const val EXTRA_MEDIA_REFERER = "media_referer"
        const val EXTRA_USE_PROXY = "use_proxy"
        const val EXTRA_REMOTE_CMD = "remote_cmd"

        private const val CHANNEL_ID = "braviacast_mirror_channel"
        private const val NOTIFICATION_ID = 2004

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var isDirectMediaActive: Boolean = false

        @Volatile
        var connectedDeviceCount: Int = 0
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var displayManager: DisplayManager? = null
    private val captureLock = Any()
    private var reusableRawBitmap: Bitmap? = null

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val audioCapturing = AtomicBoolean(false)

    private var serverSocket: ServerSocket? = null
    private val serverActive = AtomicBoolean(false)
    private var currentPort = 8888
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val frameSignal = Object()
    private val frameSeq = AtomicInteger(0)
    private var mjpegSenderThread: Thread? = null
    private val mjpegClients = CopyOnWriteArrayList<OutputStream>()
    private val audioClients = CopyOnWriteArrayList<OutputStream>()
    private val activeClientsMap = ConcurrentHashMap<String, Long>()

    private val modeVersion = AtomicInteger(1)
    private val settingsVersion = AtomicInteger(1)
    private val remoteCmdSeq = AtomicInteger(0)

    @Volatile
    private var latestRemoteCmd = ""

    private val okHttpClient = OkHttpClient()
    private var relaySocket: WebSocket? = null
    private var lastFrameSentMs = 0L

    @Volatile
    private var targetLongEdge = 1920

    @Volatile
    private var targetFps = 30

    @Volatile
    private var jpegQuality = 85

    private var currentCaptureW = 0
    private var currentCaptureH = 0

    @Volatile
    private var activeMode = "mirror" // "mirror" | "media-file" | "media-url" | "stopped"

    @Volatile
    private var activeMediaUri: Uri? = null

    @Volatile
    private var activeExternalMediaUrl: String = ""

    @Volatile
    private var activeMediaReferer: String = ""

    @Volatile
    private var activeUseProxy: Boolean = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && activeMode == "mirror") {
                reconfigureCaptureIfRotated()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REMOTE_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_REMOTE_CMD).orEmpty()
                if (cmd.isNotBlank()) {
                    latestRemoteCmd = cmd
                    remoteCmdSeq.incrementAndGet()
                }
                return START_NOT_STICKY
            }

            ACTION_UPDATE_SETTINGS -> {
                targetLongEdge = intent.getIntExtra(EXTRA_LONG_EDGE, targetLongEdge)
                targetFps = intent.getIntExtra(EXTRA_FPS, targetFps)
                jpegQuality = intent.getIntExtra(EXTRA_QUALITY, jpegQuality)
                val wantAudio = intent.getBooleanExtra(EXTRA_CAPTURE_AUDIO, true)

                if (activeMode == "mirror") {
                    reconfigureCaptureIfRotated()
                    val proj = mediaProjection
                    if (wantAudio && !audioCapturing.get() && proj != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val hasAudioPerm = ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasAudioPerm) {
                            startInternalAudioCapture(proj)
                        }
                    } else if (!wantAudio && audioCapturing.get()) {
                        stopAudioOnly()
                    }
                    settingsVersion.incrementAndGet()
                }
                return START_NOT_STICKY
            }

            ACTION_START_MEDIA_CAST -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8888)
                val uriStr = intent.getStringExtra(EXTRA_MEDIA_URI)
                val extUrl = intent.getStringExtra(EXTRA_MEDIA_URL)
                val ref = intent.getStringExtra(EXTRA_MEDIA_REFERER).orEmpty()
                val useProxy = intent.getBooleanExtra(EXTRA_USE_PROXY, false)

                stopProjectionOnly()
                startForegroundNotification(isMediaPlaybackOnly = true, withAudioCapture = false)
                acquireWakeLocks()

                if (!uriStr.isNullOrBlank()) {
                    activeMediaUri = Uri.parse(uriStr)
                    activeExternalMediaUrl = ""
                    activeMediaReferer = ""
                    activeUseProxy = false
                    activeMode = "media-file"
                } else if (!extUrl.isNullOrBlank()) {
                    activeMediaUri = null
                    activeExternalMediaUrl = extUrl
                    activeMediaReferer = ref
                    activeUseProxy = useProxy
                    activeMode = "media-url"
                }

                modeVersion.incrementAndGet()
                settingsVersion.incrementAndGet()
                isDirectMediaActive = true
                isRunning = true
                ensureHttpServerRunning(port)
                return START_NOT_STICKY
            }

            ACTION_START_MIRROR -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                targetLongEdge = intent.getIntExtra(EXTRA_LONG_EDGE, 1920)
                targetFps = intent.getIntExtra(EXTRA_FPS, 30)
                jpegQuality = intent.getIntExtra(EXTRA_QUALITY, 85)
                val port = intent.getIntExtra(EXTRA_PORT, 8888)
                val captureAudio = intent.getBooleanExtra(EXTRA_CAPTURE_AUDIO, true)
                val useRelay = intent.getBooleanExtra(EXTRA_USE_RELAY, false)
                val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE).orEmpty()
                val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL).orEmpty()

                val hasAudioPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val enableAudio = captureAudio && hasAudioPerm

                stopProjectionOnly()
                startForegroundNotification(
                    isMediaPlaybackOnly = false,
                    withAudioCapture = enableAudio
                )
                acquireWakeLocks()

                activeMode = "mirror"
                isDirectMediaActive = false
                modeVersion.incrementAndGet()
                settingsVersion.incrementAndGet()

                startProjection(resultCode, resultData, enableAudio)
                ensureHttpServerRunning(port)

                if (useRelay && roomCode.length == 6 && relayUrl.isNotBlank()) {
                    connectWebSocketRelay(relayUrl, roomCode)
                }

                isRunning = true
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(isMediaPlaybackOnly: Boolean, withAudioCapture: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TV Casting & Mirroring",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val text = if (isMediaPlaybackOnly) {
            "4K Direct Cast active (safe to turn off phone screen)"
        } else {
            "Mirroring screen & audio to TV"
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BraviaCast")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (isMediaPlaybackOnly) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else if (withAudioCapture && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BraviaCast::CastLock").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(lockMode, "BraviaCast::WifiLock").apply {
                acquire()
            }
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun computeCaptureDimensions(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val w = metrics.widthPixels.coerceAtLeast(1)
        val h = metrics.heightPixels.coerceAtLeast(1)
        val scale = targetLongEdge.toFloat() / maxOf(w, h).toFloat()

        val capW = ((w * scale).toInt().coerceAtLeast(360)) and 1.inv()
        val capH = ((h * scale).toInt().coerceAtLeast(360)) and 1.inv()
        return Triple(capW, capH, metrics.densityDpi)
    }

    private fun createConfiguredImageReader(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                val minIntervalMs = 1000L / targetFps.coerceIn(10, 60)
                if (now - lastFrameSentMs < minIntervalMs) {
                    return@setOnImageAvailableListener
                }
                lastFrameSentMs = now

                val plane = image.planes[0]
                val buffer = plane.buffer
                buffer.rewind()
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val paddedWidth = image.width + rowPadding / pixelStride
                var raw = reusableRawBitmap
                if (raw == null || raw.isRecycled || raw.width != paddedWidth || raw.height != image.height) {
                    raw?.recycle()
                    raw = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    reusableRawBitmap = raw
                }
                raw.copyPixelsFromBuffer(buffer)

                val baos = ByteArrayOutputStream()
                if (rowPadding > 0) {
                    val cropped = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
                    cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                    cropped.recycle()
                } else {
                    raw.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                }

                val jpegBytes = baos.toByteArray()
                latestJpeg.set(jpegBytes)
                frameSeq.incrementAndGet()
                synchronized(frameSignal) {
                    frameSignal.notifyAll()
                }
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, captureHandler)

        return reader
    }

    private fun startProjection(resultCode: Int, data: Intent, enableAudio: Boolean) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection

        captureThread = HandlerThread("HonorScreenCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopProjectionOnly()
                if (!isDirectMediaActive) {
                    isRunning = false
                    stopSelf()
                }
            }
        }, captureHandler)

        val (capW, capH, dpi) = computeCaptureDimensions()
        currentCaptureW = capW
        currentCaptureH = capH

        val reader = createConfiguredImageReader(capW, capH)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "Honor200SonyMirror",
            capW,
            capH,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )

        displayManager = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).also { dm ->
            dm.registerDisplayListener(displayListener, captureHandler)
        }

        if (enableAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startInternalAudioCapture(projection)
        }
    }

    private fun reconfigureCaptureIfRotated() {
        synchronized(captureLock) {
            val vd = virtualDisplay ?: return
            val (newW, newH, dpi) = computeCaptureDimensions()
            if (newW == currentCaptureW && newH == currentCaptureH) return

            currentCaptureW = newW
            currentCaptureH = newH
            val oldReader = imageReader
            val newReader = createConfiguredImageReader(newW, newH)
            imageReader = newReader
            vd.resize(newW, newH, dpi)
            vd.surface = newReader.surface
            oldReader?.close()
            reusableRawBitmap?.recycle()
            reusableRawBitmap = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startInternalAudioCapture(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val sampleRate = 44100
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
                .coerceAtLeast(8192)

            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return
            }

            audioRecord = record
            audioCapturing.set(true)
            record.startRecording()

            audioThread = Thread {
                val buf = ByteArray(4096)
                while (audioCapturing.get()) {
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0 && audioClients.isNotEmpty()) {
                        val packet = buf.copyOf(read)
                        for (client in audioClients) {
                            try {
                                client.write(packet)
                                client.flush()
                            } catch (_: Exception) {
                                try {
                                    client.close()
                                } catch (_: Exception) {
                                }
                                audioClients.remove(client)
                            }
                        }
                    }
                }
            }.apply { start() }
        } catch (_: Exception) {
        }
    }

    private fun stopAudioOnly() {
        audioCapturing.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        audioThread = null
    }

    private fun ensureHttpServerRunning(port: Int) {
        if (serverActive.get() && currentPort == port) return
        serverActive.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        currentPort = port
        serverActive.set(true)

        mjpegSenderThread = Thread {
            var lastSentSeq = -1
            while (serverActive.get()) {
                var frameToSend: ByteArray? = null
                synchronized(frameSignal) {
                    while (serverActive.get() && frameSeq.get() == lastSentSeq) {
                        try {
                            frameSignal.wait(500)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (serverActive.get()) {
                        lastSentSeq = frameSeq.get()
                        frameToSend = latestJpeg.get()
                    }
                }
                val bytes = frameToSend
                if (bytes != null) {
                    if (mjpegClients.isNotEmpty()) {
                        broadcastMjpegFrame(bytes)
                    }
                    relaySocket?.send(bytes.toByteString())
                }
            }
        }.apply { start() }

        Thread {
            try {
                val server = ServerSocket(port)
                serverSocket = server
                while (serverActive.get()) {
                    val client = server.accept()
                    client.tcpNoDelay = true
                    Thread { handleHttpClient(client) }.start()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun updateConnectedDevices(socket: Socket) {
        val now = System.currentTimeMillis()
        val ip = socket.inetAddress?.hostAddress ?: "unknown"
        activeClientsMap[ip] = now
        activeClientsMap.entries.removeIf { now - it.value > 5000L }
        connectedDeviceCount = activeClientsMap.size
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val output = socket.getOutputStream()
            when {
                requestLine.startsWith("GET /stream") -> {
                    val headers = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Cache-Control: no-cache, private\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    output.write(headers)
                    output.flush()
                    mjpegClients.add(output)
                    latestJpeg.get()?.let { initial ->
                        writeMjpegChunk(output, initial)
                    }
                }

                requestLine.startsWith("GET /audio") -> {
                    val headers = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Cache-Control: no-cache, private\r\n" +
                            "Content-Type: application/octet-stream\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    output.write(headers)
                    output.flush()
                    audioClients.add(output)
                }

                requestLine.startsWith("GET /media") -> {
                    serveMediaWithRange(output, rangeHeader)
                    socket.close()
                }

                requestLine.startsWith("GET /proxy") -> {
                    serveExternalProxyWithRange(output, rangeHeader)
                    socket.close()
                }

                requestLine.startsWith("GET /status") -> {
                    updateConnectedDevices(socket)
                    val mediaSrc = when (activeMode) {
                        "media-file" -> "/media?v=${modeVersion.get()}"
                        "media-url" -> if (activeUseProxy) "/proxy?v=${modeVersion.get()}" else activeExternalMediaUrl
                        else -> ""
                    }
                    val isHls = activeExternalMediaUrl.lowercase().contains(".m3u8")
                    val escapedSrc = mediaSrc.replace("\\", "\\\\").replace("\"", "\\\"")
                    val resLabel = if (targetLongEdge >= 1920) "1080p" else "720p"
                    val json = """{"mode":"$activeMode","version":${modeVersion.get()},"settingsVersion":${settingsVersion.get()},"mediaSrc":"$escapedSrc","isHls":$isHls,"audioActive":${audioCapturing.get()},"resLabel":"$resLabel","fps":$targetFps,"quality":$jpegQuality,"cmdSeq":${remoteCmdSeq.get()},"cmd":"$latestRemoteCmd"}"""
                        .toByteArray(StandardCharsets.UTF_8)
                    val headers = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: ${json.size}\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    output.write(headers)
                    output.write(json)
                    output.flush()
                    socket.close()
                }

                else -> {
                    updateConnectedDevices(socket)
                    val htmlBytes = buildTvViewerHtml().toByteArray(StandardCharsets.UTF_8)
                    val headers = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${htmlBytes.size}\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    output.write(headers)
                    output.write(htmlBytes)
                    output.flush()
                    socket.close()
                }
            }
        } catch (_: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun serveExternalProxyWithRange(output: OutputStream, rangeHeader: String?) {
        val url = activeExternalMediaUrl
        if (url.isBlank()) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        val reqBuilder = Request.Builder().url(url)
        if (activeMediaReferer.isNotBlank()) {
            reqBuilder.header("Referer", activeMediaReferer)
        }
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }
        okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val status = if (resp.code == 206) "206 Partial Content" else "200 OK"
            val contentType = resp.header("Content-Type") ?: "application/octet-stream"
            val contentLength = resp.header("Content-Length")
            val contentRange = resp.header("Content-Range")
            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 $status\r\n")
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
            headerBuilder.append("Content-Type: $contentType\r\n")
            if (!contentRange.isNullOrBlank()) {
                headerBuilder.append("Content-Range: $contentRange\r\n")
            }
            if (!contentLength.isNullOrBlank()) {
                headerBuilder.append("Content-Length: $contentLength\r\n")
            }
            headerBuilder.append("\r\n")
            output.write(headerBuilder.toString().toByteArray(StandardCharsets.US_ASCII))
            output.flush()

            resp.body?.byteStream()?.use { input ->
                val buf = ByteArray(65536)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                }
                output.flush()
            }
        }
    }

    private fun serveMediaWithRange(output: OutputStream, rangeHeader: String?) {
        val uri = activeMediaUri
        if (uri == null) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
            return
        }

        val mimeType = contentResolver.getType(uri) ?: "video/mp4"
        var totalSize = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) totalSize = cursor.getLong(idx)
            }
        }

        val afd = contentResolver.openAssetFileDescriptor(uri, "r") ?: return
        afd.use { descriptor ->
            if (totalSize <= 0L) {
                totalSize = descriptor.length
            }

            var start = 0L
            var end = if (totalSize > 0) totalSize - 1 else -1L
            var isPartial = false

            if (rangeHeader != null && rangeHeader.startsWith("bytes=") && totalSize > 0) {
                val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                val parts = rangeSpec.split("-")
                val parsedStart = parts.getOrNull(0)?.toLongOrNull()
                val parsedEnd = parts.getOrNull(1)?.toLongOrNull()
                if (parsedStart != null) {
                    start = parsedStart.coerceIn(0L, totalSize - 1)
                    end = (parsedEnd ?: (totalSize - 1)).coerceIn(start, totalSize - 1)
                    isPartial = true
                }
            }

            val contentLength = if (totalSize > 0) (end - start + 1) else -1L
            val headerBuilder = StringBuilder()
            if (isPartial) {
                headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
                headerBuilder.append("Content-Range: bytes $start-$end/$totalSize\r\n")
            } else {
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
            }
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            headerBuilder.append("Content-Type: $mimeType\r\n")
            if (contentLength >= 0) {
                headerBuilder.append("Content-Length: $contentLength\r\n")
            }
            headerBuilder.append("\r\n")
            output.write(headerBuilder.toString().toByteArray(StandardCharsets.US_ASCII))
            output.flush()

            descriptor.createInputStream().use { input ->
                if (start > 0) {
                    var remainingSkip = start
                    while (remainingSkip > 0) {
                        val skipped = input.skip(remainingSkip)
                        if (skipped <= 0) break
                        remainingSkip -= skipped
                    }
                }
                val buffer = ByteArray(65536)
                var remaining = if (contentLength >= 0) contentLength else Long.MAX_VALUE
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.flush()
            }
        }
    }

    private fun buildTvViewerHtml(): String {
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>BraviaCast — Smart TV Player</title>
              <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
              <style>
                * { box-sizing: border-box; }
                html, body {
                  margin: 0; padding: 0; width: 100%; height: 100%;
                  background: #05070c; color: #f8fafc;
                  font-family: system-ui, -apple-system, sans-serif;
                  overflow: hidden;
                }
                .stage {
                  position: relative; width: 100vw; height: 100vh;
                  display: flex; align-items: center; justify-content: center;
                }
                #mirrorImg, #videoPlayer {
                  width: 100%; height: 100%; object-fit: contain; display: none;
                }
                .toast {
                  position: fixed; top: 28px; left: 50%; transform: translateX(-50%);
                  background: rgba(15, 23, 42, 0.92); color: #f8fafc;
                  border: 2px solid #3b82f6; border-radius: 12px;
                  padding: 12px 24px; font-size: 20px; font-weight: 700;
                  opacity: 0; transition: opacity 0.2s ease; pointer-events: none; z-index: 30;
                }
                .toast.visible { opacity: 1; }
                .hud {
                  position: fixed; left: 32px; right: 32px; bottom: 28px;
                  display: flex; flex-direction: column; gap: 12px;
                  padding: 18px 22px; border-radius: 16px;
                  background: rgba(15, 23, 42, 0.92);
                  border: 1px solid rgba(148, 163, 184, 0.3);
                  transition: opacity 0.25s ease; z-index: 20;
                }
                .hud.hidden { opacity: 0; pointer-events: none; }
                .progress-wrap {
                  display: none; align-items: center; gap: 12px; font-size: 15px;
                  font-variant-numeric: tabular-nums; color: #cbd5e1;
                }
                .progress-wrap.active { display: flex; }
                .bar-bg {
                  flex: 1; height: 8px; border-radius: 999px; background: #334155; overflow: hidden;
                }
                .bar-fill {
                  height: 100%; width: 0%; background: #3b82f6; transition: width 0.2s linear;
                }
                .hud-row {
                  display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px;
                }
                .btn-row { display: flex; flex-wrap: wrap; gap: 10px; }
                button {
                  background: #1e293b; color: #f8fafc; border: 2px solid #334155;
                  border-radius: 10px; padding: 12px 18px; font-size: 16px; font-weight: 700;
                  cursor: pointer; transition: transform 0.12s ease, background 0.12s ease;
                }
                button:focus, button:hover {
                  background: #2563eb; border-color: #93c5fd; outline: none; transform: scale(1.06);
                }
                .hint { font-size: 14px; color: #cbd5e1; }
              </style>
            </head>
            <body tabindex="0">
              <div class="toast" id="toast"></div>
              <div class="stage" id="stage">
                <img id="mirrorImg" src="/stream" alt="Live Mirror" />
                <video id="videoPlayer" playsinline></video>
              </div>
              <div class="hud" id="hud">
                <div class="progress-wrap" id="progressWrap">
                  <span id="timeNow">00:00</span>
                  <div class="bar-bg"><div class="bar-fill" id="barFill"></div></div>
                  <span id="timeTotal">00:00</span>
                </div>
                <div class="hud-row">
                  <div class="btn-row" id="btnRow">
                    <button id="btnPlayPause" type="button" data-idx="0">Play / Pause</button>
                    <button id="btnRew" type="button" data-idx="1">-10s</button>
                    <button id="btnFwd" type="button" data-idx="2">+10s</button>
                    <button id="btnAudio" type="button" data-idx="3">Enable Sound</button>
                    <button id="btnFit" type="button" data-idx="4">Fill / Fit</button>
                    <button id="btnFull" type="button" data-idx="5">Fullscreen</button>
                  </div>
                  <div class="hint" id="statusHint">TV Remote: OK = Play/Pause · Left/Right = Seek · Down = Menu</div>
                </div>
              </div>
              <script>
                const img = document.getElementById('mirrorImg');
                const video = document.getElementById('videoPlayer');
                const hud = document.getElementById('hud');
                const toast = document.getElementById('toast');
                const hint = document.getElementById('statusHint');
                const progressWrap = document.getElementById('progressWrap');
                const timeNow = document.getElementById('timeNow');
                const timeTotal = document.getElementById('timeTotal');
                const barFill = document.getElementById('barFill');
                const buttons = Array.from(document.querySelectorAll('#btnRow button'));

                const btnPlayPause = document.getElementById('btnPlayPause');
                const btnRew = document.getElementById('btnRew');
                const btnFwd = document.getElementById('btnFwd');
                const btnAudio = document.getElementById('btnAudio');
                const btnFit = document.getElementById('btnFit');
                const btnFull = document.getElementById('btnFull');

                let currentMode = '';
                let currentVersion = -1;
                let currentSettingsVer = -1;
                let lastCmdSeq = 0;
                let hlsInstance = null;
                let mirrorFrozen = false;
                let coverMode = false;
                let audioCtx = null;
                let audioRunning = false;
                let hudTimer = null;
                let toastTimer = null;

                function showToast(msg) {
                  toast.textContent = msg;
                  toast.classList.add('visible');
                  clearTimeout(toastTimer);
                  toastTimer = setTimeout(() => toast.classList.remove('visible'), 2000);
                }

                function showHud(focusPlayBtn) {
                  hud.classList.remove('hidden');
                  if (focusPlayBtn) btnPlayPause.focus();
                  clearTimeout(hudTimer);
                  hudTimer = setTimeout(() => {
                    hud.classList.add('hidden');
                    document.body.focus();
                  }, 5000);
                }

                function hideHud() {
                  hud.classList.add('hidden');
                  document.body.focus();
                }

                function fmtTime(sec) {
                  if (!isFinite(sec) || sec < 0) return '00:00';
                  const s = Math.floor(sec);
                  const h = Math.floor(s / 3600);
                  const m = Math.floor((s % 3600) / 60);
                  const r = s % 60;
                  if (h > 0) return h + ':' + String(m).padStart(2, '0') + ':' + String(r).padStart(2, '0');
                  return String(m).padStart(2, '0') + ':' + String(r).padStart(2, '0');
                }

                video.addEventListener('timeupdate', () => {
                  if (video.duration) {
                    timeNow.textContent = fmtTime(video.currentTime);
                    timeTotal.textContent = fmtTime(video.duration);
                    barFill.style.width = Math.min(100, (video.currentTime / video.duration) * 100) + '%';
                  }
                });

                async function startAudioStream() {
                  if (audioRunning) {
                    if (audioCtx && audioCtx.state === 'suspended') await audioCtx.resume();
                    return;
                  }
                  try {
                    const AudioContextCls = window.AudioContext || window.webkitAudioContext;
                    audioCtx = new AudioContextCls({ sampleRate: 44100 });
                    await audioCtx.resume();
                    audioRunning = true;
                    btnAudio.textContent = 'Sound On';
                    showToast('TV Sound Enabled');
                    const res = await fetch('/audio');
                    const reader = res.body.getReader();
                    let nextTime = audioCtx.currentTime + 0.06;
                    let leftover = new Uint8Array(0);

                    while (audioRunning) {
                      const { value, done } = await reader.read();
                      if (done) break;
                      if (!value || value.length === 0) continue;

                      const combined = new Uint8Array(leftover.length + value.length);
                      combined.set(leftover, 0);
                      combined.set(value, leftover.length);

                      const usableBytes = combined.length - (combined.length % 4);
                      leftover = combined.slice(usableBytes);
                      if (usableBytes === 0) continue;

                      const view = new DataView(combined.buffer, combined.byteOffset, usableBytes);
                      const frames = usableBytes / 4;
                      const buffer = audioCtx.createBuffer(2, frames, 44100);
                      const left = buffer.getChannelData(0);
                      const right = buffer.getChannelData(1);

                      for (let i = 0; i < frames; i++) {
                        left[i] = view.getInt16(i * 4, true) / 32768;
                        right[i] = view.getInt16(i * 4 + 2, true) / 32768;
                      }

                      const src = audioCtx.createBufferSource();
                      src.buffer = buffer;
                      src.connect(audioCtx.destination);
                      const now = audioCtx.currentTime;
                      if (nextTime < now || nextTime - now > 0.35) {
                        nextTime = now + 0.05;
                      }
                      src.start(nextTime);
                      nextTime += buffer.duration;
                    }
                  } catch (e) {
                    audioRunning = false;
                    btnAudio.textContent = 'Enable Sound';
                  }
                }

                function togglePlayPause() {
                  if (currentMode === 'mirror') {
                    if (!audioRunning) startAudioStream();
                    mirrorFrozen = !mirrorFrozen;
                    img.src = mirrorFrozen ? '' : '/stream?t=' + Date.now();
                    showToast(mirrorFrozen ? 'Mirror Paused' : 'Mirror Playing');
                  } else {
                    if (video.paused) {
                      video.play().catch(() => {});
                      showToast('Playing');
                    } else {
                      video.pause();
                      showToast('Paused');
                    }
                  }
                }

                function seekBy(seconds) {
                  if (currentMode !== 'mirror' && video.duration) {
                    video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + seconds));
                    showToast((seconds > 0 ? '+' : '') + seconds + 's · ' + fmtTime(video.currentTime));
                  }
                }

                function toggleMute() {
                  if (currentMode === 'mirror') {
                    if (!audioRunning) {
                      startAudioStream();
                    } else if (audioCtx) {
                      if (audioCtx.state === 'running') {
                        audioCtx.suspend();
                        btnAudio.textContent = 'Sound Muted';
                        showToast('Sound Muted');
                      } else {
                        audioCtx.resume();
                        btnAudio.textContent = 'Sound On';
                        showToast('Sound On');
                      }
                    }
                  } else {
                    video.muted = !video.muted;
                    btnAudio.textContent = video.muted ? 'Sound Muted' : 'Sound On';
                    showToast(video.muted ? 'Sound Muted' : 'Sound On');
                  }
                }

                btnPlayPause.addEventListener('click', () => { togglePlayPause(); showHud(false); });
                btnRew.addEventListener('click', () => { seekBy(-10); showHud(false); });
                btnFwd.addEventListener('click', () => { seekBy(10); showHud(false); });
                btnAudio.addEventListener('click', () => { toggleMute(); showHud(false); });
                btnFit.addEventListener('click', () => {
                  coverMode = !coverMode;
                  const fit = coverMode ? 'cover' : 'contain';
                  img.style.objectFit = fit;
                  video.style.objectFit = fit;
                  showToast(coverMode ? 'Fill Screen' : 'Fit Aspect');
                  showHud(false);
                });
                btnFull.addEventListener('click', () => {
                  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
                  else document.documentElement.requestFullscreen().catch(() => {});
                  showHud(false);
                });

                // Smart TV D-Pad Remote Navigation (Zero pointer required!)
                document.addEventListener('keydown', (e) => {
                  const hudVisible = !hud.classList.contains('hidden');
                  const focusedIdx = buttons.indexOf(document.activeElement);

                  if (e.key === 'MediaPlayPause' || e.key === ' ') {
                    e.preventDefault();
                    togglePlayPause();
                    return;
                  }
                  if (e.key === 'MediaFastForward') {
                    e.preventDefault();
                    seekBy(10);
                    return;
                  }
                  if (e.key === 'MediaRewind') {
                    e.preventDefault();
                    seekBy(-10);
                    return;
                  }

                  if (!hudVisible) {
                    if (e.key === 'Enter' || e.key === 'OK') {
                      e.preventDefault();
                      togglePlayPause();
                      showHud(true);
                    } else if (e.key === 'ArrowRight') {
                      e.preventDefault();
                      seekBy(10);
                    } else if (e.key === 'ArrowLeft') {
                      e.preventDefault();
                      seekBy(-10);
                    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                      e.preventDefault();
                      showHud(true);
                    }
                    return;
                  }

                  // When HUD menu is open, D-Pad Left/Right switches focused button cleanly
                  showHud(false);
                  if (e.key === 'ArrowRight') {
                    e.preventDefault();
                    const next = focusedIdx >= 0 ? (focusedIdx + 1) % buttons.length : 0;
                    buttons[next].focus();
                  } else if (e.key === 'ArrowLeft') {
                    e.preventDefault();
                    const prev = focusedIdx >= 0 ? (focusedIdx - 1 + buttons.length) % buttons.length : 0;
                    buttons[prev].focus();
                  } else if (e.key === 'ArrowUp' || e.key === 'Escape' || e.key === 'Backspace') {
                    e.preventDefault();
                    hideHud();
                  } else if (e.key === 'Enter' && focusedIdx < 0) {
                    e.preventDefault();
                    btnPlayPause.focus();
                    togglePlayPause();
                  }
                });

                document.addEventListener('mousemove', () => showHud(false));
                document.addEventListener('click', (e) => {
                  if (e.target === img || e.target === video || e.target === document.getElementById('stage')) {
                    if (currentMode === 'mirror' && !audioRunning) startAudioStream();
                    showHud(true);
                  }
                });

                img.addEventListener('error', () => {
                  if (currentMode === 'mirror' && !mirrorFrozen) {
                    setTimeout(() => {
                      if (currentMode === 'mirror' && !mirrorFrozen) {
                        img.src = '/stream?retry=' + Date.now();
                      }
                    }, 900);
                  }
                });

                async function pollStatus() {
                  try {
                    const res = await fetch('/status');
                    const data = await res.json();

                    if (data.cmdSeq && data.cmdSeq !== lastCmdSeq) {
                      lastCmdSeq = data.cmdSeq;
                      if (data.cmd === 'playpause') togglePlayPause();
                      else if (data.cmd === 'mute') toggleMute();
                      else if (data.cmd && data.cmd.startsWith('seek:')) {
                        seekBy(Number(data.cmd.split(':')[1] || 10));
                      }
                    }

                    const modeChanged = data.version !== currentVersion || data.mode !== currentMode;
                    const settingsChanged = data.settingsVersion !== currentSettingsVer;

                    if (modeChanged || settingsChanged) {
                      currentVersion = data.version;
                      currentMode = data.mode;
                      currentSettingsVer = data.settingsVersion;

                      if (settingsChanged && !modeChanged && data.mode === 'mirror') {
                        img.src = '/stream?v=' + data.settingsVersion + '&t=' + Date.now();
                        showToast(data.resLabel + ' · ' + data.fps + 'fps · ' + (data.audioActive ? 'Audio On' : 'Muted'));
                        return;
                      }

                      if (hlsInstance) {
                        hlsInstance.destroy();
                        hlsInstance = null;
                      }
                      if (data.mode === 'mirror') {
                        video.pause();
                        video.removeAttribute('src');
                        video.style.display = 'none';
                        progressWrap.classList.remove('active');
                        img.style.display = 'block';
                        img.src = '/stream?t=' + Date.now();
                        hint.textContent = 'Live Mirror · Press OK for Sound/Pause · Down for Menu';
                      } else if (data.mediaSrc) {
                        img.style.display = 'none';
                        video.style.display = 'block';
                        progressWrap.classList.add('active');
                        if (data.isHls && window.Hls && Hls.isSupported()) {
                          hlsInstance = new Hls();
                          hlsInstance.loadSource(data.mediaSrc);
                          hlsInstance.attachMedia(video);
                          hlsInstance.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
                        } else {
                          video.src = data.mediaSrc;
                          video.play().catch(() => {});
                        }
                        hint.textContent = '4K Video · OK = Play/Pause · Left/Right = -10s/+10s · Down = Menu';
                      }
                    }
                  } catch (_e) {}
                }

                showHud(true);
                pollStatus();
                setInterval(pollStatus, 800);
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun broadcastMjpegFrame(jpeg: ByteArray) {
        for (client in mjpegClients) {
            if (!writeMjpegChunk(client, jpeg)) {
                mjpegClients.remove(client)
            }
        }
    }

    private fun writeMjpegChunk(output: OutputStream, jpeg: ByteArray): Boolean {
        return try {
            val partHeader = (
                "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${jpeg.size}\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
            output.write(partHeader)
            output.write(jpeg)
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            true
        } catch (_: Exception) {
            try {
                output.close()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun connectWebSocketRelay(baseUrl: String, roomCode: String) {
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val fullUrl = "${baseUrl}${separator}role=sender&code=$roomCode&device=Honor%20200%20Pro"
        val request = Request.Builder().url(fullUrl).build()
        relaySocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"stream-meta","sourceType":"mjpeg-ws","resolution":"1080p"}""")
            }
        })
    }

    private fun stopProjectionOnly() {
        stopAudioOnly()

        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        displayManager = null

        synchronized(captureLock) {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            reusableRawBitmap?.recycle()
            reusableRawBitmap = null
        }
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
    }

    private fun stopEverything() {
        isRunning = false
        isDirectMediaActive = false
        activeMode = "stopped"
        connectedDeviceCount = 0
        stopProjectionOnly()

        serverActive.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        for (client in mjpegClients) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        mjpegClients.clear()

        for (client in audioClients) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        audioClients.clear()

        relaySocket?.send("""{"type":"stop-stream"}""")
        relaySocket?.close(1000, "Stopped")
        relaySocket = null
        releaseWakeLocks()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}
