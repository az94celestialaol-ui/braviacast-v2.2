package com.honorcast.sonybridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

data class DetectedVideoStream(
    val url: String,
    val referer: String
)

class MainActivity : ComponentActivity() {

    private var sharedVideoUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractSharedVideo(intent)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF8FAFC)
                ) {
                    MirrorHomeScreen(
                        incomingVideoUri = sharedVideoUri,
                        onClearIncomingVideo = { sharedVideoUri = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractSharedVideo(intent)
    }

    private fun extractSharedVideo(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                sharedVideoUri = uri
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MirrorHomeScreen(
    incomingVideoUri: Uri?,
    onClearIncomingVideo: () -> Unit
) {
    val context = LocalContext.current
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Mirror, 1 = Web 4K Caster, 2 = File/URL
    var isMirroring by remember { mutableStateOf(ScreenMirrorService.isRunning) }
    var isMediaCasting by remember { mutableStateOf(ScreenMirrorService.isDirectMediaActive) }
    var connectedDevices by remember { mutableIntStateOf(ScreenMirrorService.connectedDeviceCount) }

    var captureAudio by remember { mutableStateOf(true) }
    var useRelayMode by remember { mutableStateOf(false) }
    var roomCode by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf("wss://ais-dev-xdb5qyk4jlqnddje3rj4cg-607933069998.asia-northeast1.run.app/ws") }
    var portText by remember { mutableStateOf("8888") }
    var longEdge by remember { mutableIntStateOf(1920) }
    var targetFps by remember { mutableIntStateOf(30) }
    var quality by remember { mutableIntStateOf(85) }

    var directStreamUrl by remember { mutableStateOf("") }
    var browserUrlInput by remember { mutableStateOf("https://") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val detectedStreams = remember { mutableStateListOf<DetectedVideoStream>() }
    var statusMessage by remember { mutableStateOf("Ready for Sony Google TV & same-Wi-Fi devices") }

    val wifiIp = remember { getLocalWifiIpAddress(context) }

    fun pushLiveSettingsUpdate(
        nextLongEdge: Int = longEdge,
        nextFps: Int = targetFps,
        nextQuality: Int = quality,
        nextAudio: Boolean = captureAudio
    ) {
        if (!isMirroring || isMediaCasting) return
        val updateIntent = Intent(context, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_UPDATE_SETTINGS
            putExtra(ScreenMirrorService.EXTRA_LONG_EDGE, nextLongEdge)
            putExtra(ScreenMirrorService.EXTRA_FPS, nextFps)
            putExtra(ScreenMirrorService.EXTRA_QUALITY, nextQuality)
            putExtra(ScreenMirrorService.EXTRA_CAPTURE_AUDIO, nextAudio)
        }
        context.startService(updateIntent)
        statusMessage = "Updated live on TV: ${if (nextLongEdge >= 1920) "1080p" else "720p"} · ${nextFps}fps · Q${nextQuality}%"
    }

    fun sendTvRemoteCommand(cmd: String) {
        if (!isMirroring) return
        val cmdIntent = Intent(context, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_REMOTE_COMMAND
            putExtra(ScreenMirrorService.EXTRA_REMOTE_CMD, cmd)
        }
        context.startService(cmdIntent)
    }

    LaunchedEffect(isMirroring, isMediaCasting) {
        while (true) {
            isMirroring = ScreenMirrorService.isRunning
            isMediaCasting = ScreenMirrorService.isDirectMediaActive
            connectedDevices = ScreenMirrorService.connectedDeviceCount
            delay(1200)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val captureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val port = portText.toIntOrNull() ?: 8888
            val serviceIntent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START_MIRROR
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenMirrorService.EXTRA_PORT, port)
                putExtra(ScreenMirrorService.EXTRA_LONG_EDGE, longEdge)
                putExtra(ScreenMirrorService.EXTRA_FPS, targetFps)
                putExtra(ScreenMirrorService.EXTRA_QUALITY, quality)
                putExtra(ScreenMirrorService.EXTRA_CAPTURE_AUDIO, captureAudio)
                putExtra(ScreenMirrorService.EXTRA_USE_RELAY, useRelayMode)
                putExtra(ScreenMirrorService.EXTRA_ROOM_CODE, roomCode.trim())
                putExtra(ScreenMirrorService.EXTRA_RELAY_URL, relayUrl.trim())
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            isMirroring = true
            isMediaCasting = false
            statusMessage = "Live Mirror active -> Open http://$wifiIp:$port on any TV or device"
        } else {
            statusMessage = "Screen capture permission was cancelled."
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val port = portText.toIntOrNull() ?: 8888
            val mediaIntent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START_MEDIA_CAST
                putExtra(ScreenMirrorService.EXTRA_PORT, port)
                putExtra(ScreenMirrorService.EXTRA_MEDIA_URI, uri.toString())
            }
            ContextCompat.startForegroundService(context, mediaIntent)
            isMirroring = true
            isMediaCasting = true
            statusMessage = "4K Video File Cast active (phone screen can be turned OFF)"
        }
    }

    LaunchedEffect(incomingVideoUri) {
        if (incomingVideoUri != null) {
            val port = portText.toIntOrNull() ?: 8888
            val mediaIntent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START_MEDIA_CAST
                putExtra(ScreenMirrorService.EXTRA_PORT, port)
                putExtra(ScreenMirrorService.EXTRA_MEDIA_URI, incomingVideoUri.toString())
            }
            ContextCompat.startForegroundService(context, mediaIntent)
            isMirroring = true
            isMediaCasting = true
            statusMessage = "Shared video streaming directly in native 4K (screen can stay OFF)"
            onClearIncomingVideo()
        }
    }

    fun castExternalStream(url: String, referer: String, useProxy: Boolean) {
        val port = portText.toIntOrNull() ?: 8888
        val mediaIntent = Intent(context, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_START_MEDIA_CAST
            putExtra(ScreenMirrorService.EXTRA_PORT, port)
            putExtra(ScreenMirrorService.EXTRA_MEDIA_URL, url)
            putExtra(ScreenMirrorService.EXTRA_MEDIA_REFERER, referer)
            putExtra(ScreenMirrorService.EXTRA_USE_PROXY, useProxy)
        }
        ContextCompat.startForegroundService(context, mediaIntent)
        isMirroring = true
        isMediaCasting = true
        statusMessage = "4K Web Stream playing on TV (phone screen can be turned OFF)"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BraviaCast",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Text(
                text = "TVs Connected: $connectedDevices",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (connectedDevices > 0) Color(0xFF15803D) else Color(0xFF64748B)
            )
        }

        // Address Card for any TV or device on same Wi-Fi
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Open on Sony TV / Any Smart TV / Device (Same Wi-Fi)",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF334155)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "http://$wifiIp:$portText",
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2563EB)
                )
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("TV URL", "http://$wifiIp:$portText")
                        )
                        statusMessage = "Copied http://$wifiIp:$portText"
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Copy", fontSize = 12.sp)
                }
            }
            Text(
                text = statusMessage,
                fontSize = 12.sp,
                color = Color(0xFF475569)
            )
        }

        // Phone-side Live TV Remote Control Bar (always visible when casting!)
        if (isMirroring) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isMediaCasting) "Phone Remote Control (Controls TV Player)" else "TV Display Controls",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE2E8F0)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isMediaCasting) {
                        OutlinedButton(
                            onClick = { sendTvRemoteCommand("seek:-10") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("-10s", fontSize = 12.sp) }
                    }
                    Button(
                        onClick = { sendTvRemoteCommand("playpause") },
                        modifier = Modifier.weight(1.3f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Play / Pause", fontSize = 12.sp) }
                    if (isMediaCasting) {
                        OutlinedButton(
                            onClick = { sendTvRemoteCommand("seek:10") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("+10s", fontSize = 12.sp) }
                    }
                    OutlinedButton(
                        onClick = { sendTvRemoteCommand("mute") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Mute", fontSize = 12.sp) }
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Screen Mirror", fontSize = 12.sp) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("4K Web Caster", fontSize = 12.sp) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("4K File / URL", fontSize = 12.sp) }
            )
        }

        if (selectedTab == 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Live Mirror Settings (Updates TV in Real Time)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1280 to "720p Fast", 1920 to "1080p Sharp").forEach { (edge, label) ->
                        val selected = longEdge == edge
                        OutlinedButton(
                            onClick = {
                                longEdge = edge
                                pushLiveSettingsUpdate(nextLongEdge = edge)
                            },
                            modifier = Modifier.weight(1f),
                            colors = if (selected) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0xFFDBEAFE),
                                    contentColor = Color(0xFF1D4ED8)
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(30 to "30 fps", 60 to "60 fps Smooth").forEach { (fpsVal, label) ->
                        val selected = targetFps == fpsVal
                        OutlinedButton(
                            onClick = {
                                targetFps = fpsVal
                                pushLiveSettingsUpdate(nextFps = fpsVal)
                            },
                            modifier = Modifier.weight(1f),
                            colors = if (selected) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0xFFDBEAFE),
                                    contentColor = Color(0xFF1D4ED8)
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Column {
                    Text(
                        text = "Sharpness / Quality: $quality% (Changes Live)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF334155)
                    )
                    Slider(
                        value = quality.toFloat(),
                        onValueChange = { quality = it.toInt() },
                        onValueChangeFinished = { pushLiveSettingsUpdate(nextQuality = quality) },
                        valueRange = 60f..95f
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stream Phone Audio to TV", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Updates live · Press OK once on TV remote to unmute", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Switch(
                        checked = captureAudio,
                        onCheckedChange = { enabled ->
                            captureAudio = enabled
                            if (enabled) ensurePermissions(context, true, permissionLauncher)
                            pushLiveSettingsUpdate(nextAudio = enabled)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Also Stream to Web TV Receiver", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("6-digit code pairing", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Switch(checked = useRelayMode, onCheckedChange = { useRelayMode = it })
                }

                if (useRelayMode) {
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { roomCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("TV Receiver 6-Digit Code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = relayUrl,
                        onValueChange = { relayUrl = it },
                        label = { Text("WebSocket Relay URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = {
                        if (isMirroring) {
                            val stopIntent = Intent(context, ScreenMirrorService::class.java).apply {
                                action = ScreenMirrorService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                            isMirroring = false
                            isMediaCasting = false
                            statusMessage = "Stopped."
                        } else {
                            ensurePermissions(context, captureAudio, permissionLauncher)
                            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMirroring) Color(0xFFDC2626) else Color(0xFF2563EB)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (isMirroring) "Stop" else "Start Live Screen Mirror + Audio",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else if (selectedTab == 1) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "4K Web Video Caster (No Download · Phone Screen Off)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "Open any video site below and press Play. Detected .mp4 / .m3u8 streams appear below—tap Cast to play directly on your Sony TV in native 4K with remote controls.",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("https://vimeo.com" to "Vimeo", "https://www.dailymotion.com" to "Dailymotion").forEach { (url, label) ->
                        OutlinedButton(
                            onClick = {
                                browserUrlInput = url
                                detectedStreams.clear()
                                webViewRef?.loadUrl(url)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) { Text(label, fontSize = 11.sp) }
                    }
                    OutlinedButton(
                        onClick = { webViewRef?.goBack() },
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Back", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { webViewRef?.reload() },
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Reload", fontSize = 11.sp) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = browserUrlInput,
                        onValueChange = { browserUrlInput = it },
                        label = { Text("Website URL") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val trimmed = browserUrlInput.trim()
                            val urlToLoad = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                trimmed
                            } else {
                                "https://$trimmed"
                            }
                            detectedStreams.clear()
                            webViewRef?.loadUrl(urlToLoad)
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Go")
                    }
                }

                if (detectedStreams.isNotEmpty()) {
                    Text(
                        text = "Detected Streams (${detectedStreams.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1D4ED8)
                    )
                    detectedStreams.take(4).forEach { stream ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stream.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val isM3u8 = stream.url.lowercase().contains(".m3u8")
                                    castExternalStream(stream.url, stream.referer, useProxy = !isM3u8)
                                },
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Cast", fontSize = 11.sp)
                            }
                        }
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString().orEmpty()
                                    val lower = reqUrl.lowercase()
                                    val isVideo = lower.contains(".m3u8") ||
                                        lower.contains(".mp4") ||
                                        lower.contains(".webm") ||
                                        lower.contains("mime=video")
                                    if (isVideo && reqUrl.startsWith("http")) {
                                        val ref = request?.requestHeaders?.get("Referer").orEmpty()
                                        view?.post {
                                            if (detectedStreams.none { it.url == reqUrl }) {
                                                detectedStreams.add(0, DetectedVideoStream(reqUrl, ref))
                                            }
                                        }
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }
                            webViewRef = this
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Direct 4K Video File / Link (Phone Screen Off + TV Remote)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "Streams the original 4K video directly to your TV hardware without following your phone screen. You can lock your phone screen and control Play/Pause/Fast-Forward with your Sony TV remote or the Phone Remote above.",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
                Button(
                    onClick = {
                        ensurePermissions(context, false, permissionLauncher)
                        videoPickerLauncher.launch(arrayOf("video/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Pick 4K Video File from Phone")
                }

                OutlinedTextField(
                    value = directStreamUrl,
                    onValueChange = { directStreamUrl = it },
                    label = { Text("Or Paste Direct .mp4 / .m3u8 URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (directStreamUrl.isNotBlank()) {
                    Button(
                        onClick = { castExternalStream(directStreamUrl.trim(), "", useProxy = false) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cast Video URL in Native 4K")
                    }
                }
            }
        }

        if (isMirroring) {
            Button(
                onClick = {
                    val stopIntent = Intent(context, ScreenMirrorService::class.java).apply {
                        action = ScreenMirrorService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                    isMirroring = false
                    isMediaCasting = false
                    statusMessage = "Casting stopped."
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (isMediaCasting) "Stop 4K Cast" else "Stop Mirroring")
            }
        }

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit) },
            label = { Text("Local Wi-Fi Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = { requestMagicOsBatteryExemption(context) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Allow Background & Screen-Off Playback (MagicOS 8)")
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

private fun ensurePermissions(
    context: Context,
    needAudio: Boolean,
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val missing = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (needAudio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        missing.add(Manifest.permission.RECORD_AUDIO)
    }
    if (missing.isNotEmpty()) {
        launcher.launch(missing.toTypedArray())
    }
}

private fun requestMagicOsBatteryExemption(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}

private fun getLocalWifiIpAddress(context: Context): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    val host = address.hostAddress ?: continue
                    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                        return host
                    }
                }
            }
        }
    } catch (_: Exception) {
    }
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Suppress("DEPRECATION")
    val ipInt = wifiManager.connectionInfo.ipAddress
    if (ipInt != 0) {
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff,
            ipInt shr 24 and 0xff
        )
    }
    return "192.168.x.x"
}
