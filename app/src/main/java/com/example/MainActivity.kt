package com.example

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// Beautiful color definitions to avoid generic AI defaults
val ThemeDeepBg = Color(0xFF0B0F19)
val ThemeCardBg = Color(0xFF151D30)
val ThemeCardBgLight = Color(0xFF1E294B)
val ThemeAccentTeal = Color(0xFF10B981)
val ThemeAccentCyan = Color(0xFF06B6D4)
val ThemeTextPrimary = Color(0xFFF8FAFC)
val ThemeTextSecondary = Color(0xFF94A3B8)
val ThemeError = Color(0xFFEF4444)

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Activity launcher for capturing permission
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenSharingService::class.java).apply {
                putExtra(ScreenSharingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenSharingService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenSharingService.EXTRA_ROOM_CODE, generatedRoomCode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isServiceSharing = true
        } else {
            Toast.makeText(this, "Screen capture permission is required to start sharing.", Toast.LENGTH_LONG).show()
        }
    }

    // Dynamic states for background service bridging
    private var generatedRoomCode by mutableStateOf("")
    private var isServiceSharing by mutableStateOf(false)

    // Activity registration for POST_NOTIFICATIONS on Android 13
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed anyway, standard foreground manager handles fallback if permission denied
        requestScreenSharingIntent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        enableEdgeToEdge()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = ThemeDeepBg,
                    surface = ThemeCardBg,
                    primary = ThemeAccentTeal,
                    secondary = ThemeAccentCyan,
                    error = ThemeError
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ThemeDeepBg
                ) {
                    MainAppContent(
                        generatedRoomCode = { generatedRoomCode },
                        setGeneratedRoomCode = { generatedRoomCode = it },
                        isServiceSharing = { isServiceSharing || ScreenSharingService.isSharingRunning },
                        setServiceSharing = { isServiceSharing = it },
                        onRequestSharingStart = { initiateScreenSharing() },
                        onRequestSharingStop = { terminateScreenSharing() }
                    )
                }
            }
        }
    }

    private fun initiateScreenSharing() {
        if (generatedRoomCode.isEmpty()) {
            generatedRoomCode = List(6) { Random.nextInt(0, 10) }.joinToString("")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasNotificationPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestScreenSharingIntent()
            }
        } else {
            requestScreenSharingIntent()
        }
    }

    private fun requestScreenSharingIntent() {
        try {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error triggering screen capture permission intent", e)
            Toast.makeText(this, "Your device does not support screen sharing.", Toast.LENGTH_LONG).show()
        }
    }

    private fun terminateScreenSharing() {
        val stopIntent = Intent(this, ScreenSharingService::class.java).apply {
            action = ScreenSharingService.ACTION_STOP_SHARING
        }
        startService(stopIntent)
        isServiceSharing = false
        generatedRoomCode = ""
    }

    override fun onResume() {
        super.onResume()
        // Sync state if service running in background
        if (ScreenSharingService.isSharingRunning) {
            isServiceSharing = true
            generatedRoomCode = ScreenSharingService.roomCode
        }
    }
}

// Global UI state model
class MainViewModel : ViewModel() {
    private val _currentMode = MutableStateFlow(0) // 0: Select, 1: Share, 2: Join
    val currentMode = _currentMode.asStateFlow()

    private val _statusText = MutableStateFlow("Disconnected")
    val statusText = _statusText.asStateFlow()

    private val _liveFrame = MutableStateFlow<Bitmap?>(null)
    val liveFrame = _liveFrame.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var streamWebSocket: WebSocket? = null
    private var scope = CoroutineScope(Dispatchers.IO + Job())

    fun selectMode(mode: Int) {
        _currentMode.value = mode
        if (mode == 0) {
            disconnectJoiner()
        }
    }

    // Joiner logic: Connects to the host's signaling/stream feeds
    fun connectAsJoiner(code: String, onConnectionResult: (Boolean, String) -> Unit) {
        if (code.length != 6 || !code.all { it.isDigit() }) {
            onConnectionResult(false, "Invalid 6-digit room code format.")
            return
        }

        _isConnecting.value = true
        _statusText.value = "Locating sharing room..."

        // Subscribe to Host events topic
        val wsUrl = "wss://ntfy.sh/qs_${code}_host/ws"
        val request = Request.Builder().url(wsUrl).build()

        streamWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Connection channel opened, notify Host that viewer has attached
                _isConnecting.value = false
                _statusText.value = "Connected"
                onConnectionResult(true, "Successfully connected to screen share!")
                notifyHostOfJoin(code)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val rawMessage = root.optString("message")
                    if (!rawMessage.isNullOrEmpty()) {
                        val payload = JSONObject(rawMessage)
                        when (payload.optString("type")) {
                            "host_frame" -> {
                                val base64Frame = payload.optString("base64")
                                if (base64Frame.isNotEmpty()) {
                                    val jpegBytes = Base64.decode(base64Frame, Base64.DEFAULT)
                                    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                    if (bitmap != null) {
                                        _liveFrame.value = bitmap
                                        _statusText.value = "Connected (Live visual stream)"
                                    }
                                }
                            }
                            "host_left" -> {
                                _liveFrame.value = null
                                _statusText.value = "Disconnected (Host left room)"
                                webSocket.close(1000, "Host hung up screen")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error reading streaming frame data", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnecting.value = false
                _liveFrame.value = null
                _statusText.value = "Failed connecting to room. Verification timed out."
                onConnectionResult(false, "Network error: Connection unable to establish.")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _liveFrame.value = null
                _statusText.value = "Disconnected"
            }
        })
    }

    private fun notifyHostOfJoin(code: String) {
        scope.launch {
            try {
                val payload = JSONObject().apply { put("type", "client_joined") }
                val request = Request.Builder()
                    .url("https://ntfy.sh/qs_${code}_feedback")
                    .post(payload.toString().toRequestBody())
                    .build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error alerting host of viewer join state", e)
            }
        }
    }

    fun disconnectJoiner() {
        streamWebSocket?.close(1000, "User left")
        _liveFrame.value = null
        _statusText.value = "Disconnected"
    }

    override fun onCleared() {
        disconnectJoiner()
        super.onCleared()
    }
}

@Composable
fun MainAppContent(
    generatedRoomCode: () -> String,
    setGeneratedRoomCode: (String) -> Unit,
    isServiceSharing: () -> Boolean,
    setServiceSharing: (Boolean) -> Unit,
    onRequestSharingStart: () -> Unit,
    onRequestSharingStop: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()
    val statusText by viewModel.statusText.collectAsStateWithLifecycle()
    val liveFrame by viewModel.liveFrame.collectAsStateWithLifecycle()
    val isConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()

    val currentRoomCode = generatedRoomCode()
    val isSharing = isServiceSharing()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = ThemeDeepBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Elegant Visual Header
            HeaderView(
                currentMode = currentMode,
                onBackClicked = { viewModel.selectMode(0) }
            )

            AnimatedContent(
                targetState = currentMode,
                transitionSpec = {
                    slideInHorizontally { width -> if (targetState > initialState) width else -width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> if (targetState > initialState) -width else width } + fadeOut()
                },
                label = "ScreenTransition"
            ) { mode ->
                when (mode) {
                    0 -> ModeSelectorView(
                        onShareSelected = {
                            viewModel.selectMode(1)
                        },
                        onJoinSelected = {
                            viewModel.selectMode(2)
                        }
                    )
                    1 -> ShareModeView(
                        roomCode = currentRoomCode,
                        isSharing = isSharing,
                        onStartSharing = { onRequestSharingStart() },
                        onStopSharing = { onRequestSharingStop() }
                    )
                    2 -> JoinModeView(
                        statusText = statusText,
                        liveFrame = liveFrame,
                        isConnecting = isConnecting,
                        onJoin = { code ->
                            viewModel.connectAsJoiner(code) { success, message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDisconnect = {
                            viewModel.selectMode(0)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun HeaderView(currentMode: Int, onBackClicked: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (currentMode != 0) {
            IconButton(
                onClick = onBackClicked,
                modifier = Modifier
                    .background(ThemeCardBg, CircleShape)
                    .size(48.dp)
                    .testTag("back_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = ThemeTextPrimary
                )
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }

        Text(
            text = "QuickShare Screen",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                fontSize = 22.sp,
                letterSpacing = 0.5.sp
            ),
            color = ThemeTextPrimary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )

        IconButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .background(ThemeCardBg.copy(alpha = 0.3f), CircleShape)
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                tint = ThemeAccentTeal
            )
        }
    }
}

@Composable
fun ModeSelectorView(onShareSelected: () -> Unit, onJoinSelected: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(ThemeCardBg, ThemeDeepBg)
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .border(1.dp, ThemeCardBgLight, RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(ThemeAccentTeal.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = "QuickShare Screen Logo",
                        tint = ThemeAccentTeal,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Cast Your Phone Live",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = ThemeTextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Stream pictures, maps, sheets or documents instantly to any device on the web using high-efficiency encrypted base channels.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ThemeTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // High contrast Option 1: Share screen card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(ThemeCardBg)
                .clickable { onShareSelected() }
                .border(2.dp, ThemeAccentTeal.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .testTag("share_my_screen_card")
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(ThemeAccentTeal.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ScreenShare,
                        contentDescription = null,
                        tint = ThemeAccentTeal,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                Column {
                    Text(
                        text = "Share My Screen",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ThemeTextPrimary
                    )
                    Text(
                        text = "Generate a code to start streaming graphics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ThemeTextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // High contrast Option 2: Join with code card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(ThemeCardBg)
                .clickable { onJoinSelected() }
                .border(1.dp, ThemeCardBgLight, RoundedCornerShape(24.dp))
                .testTag("join_with_code_card")
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(ThemeAccentCyan.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = null,
                        tint = ThemeAccentCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                Column {
                    Text(
                        text = "Join With Code",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ThemeTextPrimary
                    )
                    Text(
                        text = "Input a 6-digit code to view sharing screens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ThemeTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun ShareModeView(
    roomCode: String,
    isSharing: Boolean,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val infiniteTransition = rememberInfiniteTransition(label = "pulsating")
    val scalePulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnimation"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!isSharing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Inactive sharing",
                        tint = ThemeTextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Sharing Offline",
                        style = MaterialTheme.typography.bodyLarge,
                        color = ThemeTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onStartSharing,
                colors = ButtonDefaults.buttonColors(containerColor = ThemeAccentTeal),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("start_sharing_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start Sharing Screen",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
        } else {
            // Sharing state details
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ThemeCardBg, RoundedCornerShape(24.dp))
                    .border(2.dp, ThemeAccentTeal, RoundedCornerShape(24.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    
                    // Connected indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .scale(scalePulse)
                                .size(12.dp)
                                .background(ThemeAccentTeal, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIVE SHARING ROOM",
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                            color = ThemeAccentTeal,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "QuickRoom Code",
                        style = MaterialTheme.typography.bodySmall,
                        color = ThemeTextSecondary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // The scannable 6-digit styled digits
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        roomCode.chunked(3).forEachIndexed { i, portion ->
                            Text(
                                text = portion,
                                style = MaterialTheme.typography.displayMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 42.sp,
                                    letterSpacing = 4.sp
                                ),
                                color = ThemeTextPrimary
                            )
                            if (i == 0) {
                                Text(
                                    text = "-",
                                    style = MaterialTheme.typography.displayMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 32.sp
                                    ),
                                    color = ThemeAccentTeal,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons (Copy / Share Code)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("quickshare_code", roomCode))
                                Toast.makeText(context, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeCardBgLight),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy code",
                                tint = ThemeTextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Code", color = ThemeTextPrimary)
                        }

                        Button(
                            onClick = {
                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Join my live screen sharing on QuickShare Screen with 6-digit code: $roomCode")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Screen Code Via"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeCardBgLight),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Share",
                                tint = ThemeAccentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share Link", color = ThemeTextPrimary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Massive red Stop sharing action
            Button(
                onClick = onStopSharing,
                colors = ButtonDefaults.buttonColors(containerColor = ThemeError),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("stop_sharing_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Stop Sharing Screen",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun JoinModeView(
    statusText: String,
    liveFrame: Bitmap?,
    isConnecting: Boolean,
    onJoin: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    var codeInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (liveFrame == null) {
            Text(
                text = "Enter 6-Digit Code to Joint Stream",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = ThemeTextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Digit entry box
            OutlinedTextField(
                value = codeInput,
                onValueChange = {
                    if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                        codeInput = it
                    }
                },
                placeholder = { Text("000000", color = ThemeTextSecondary.copy(alpha = 0.5f)) },
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 12.sp,
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        focusManager.clearFocus()
                        if (codeInput.length == 6) {
                            onJoin(codeInput)
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ThemeAccentCyan,
                    unfocusedBorderColor = ThemeCardBgLight,
                    focusedTextColor = ThemeTextPrimary,
                    unfocusedTextColor = ThemeTextPrimary,
                    focusedContainerColor = ThemeCardBg,
                    unfocusedContainerColor = ThemeCardBg
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .testTag("code_input_field")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onJoin(codeInput)
                },
                enabled = codeInput.length == 6 && !isConnecting,
                colors = ButtonDefaults.buttonColors(containerColor = ThemeAccentCyan),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("connect_viewer_button")
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.Launch,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Join Channel Activity",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Connection feedback log
            if (statusText != "Disconnected") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ThemeCardBg.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = statusText,
                        color = if (statusText.contains("Failed") || statusText.contains("Error")) ThemeError else ThemeAccentCyan,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Live sharing frame canvas viewport card!
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ThemeCardBg, RoundedCornerShape(24.dp))
                    .border(2.dp, ThemeAccentCyan, RoundedCornerShape(24.dp))
                    .padding(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(ThemeAccentCyan, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LIVE VIEWPORT",
                                style = MaterialTheme.typography.labelMedium,
                                color = ThemeTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = ThemeTextSecondary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f) // Screen aspect ratio compatibility match
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = liveFrame.asImageBitmap(),
                            contentDescription = "Active live streaming host screen capture frame",
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = ThemeError),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("exit_viewer_button")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disconnect Viewer", color = Color.White)
                    }
                }
            }
        }
    }
}
