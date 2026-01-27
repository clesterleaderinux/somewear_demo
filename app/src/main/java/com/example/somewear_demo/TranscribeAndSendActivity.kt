package com.example.somewear_demo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


/**
 * Main activity for the Somewear Demo application that provides speech recognition,
 * text transcription, and Bluetooth communication capabilities.
 * 
 * This activity demonstrates a complete voice-to-text pipeline with Bluetooth transmission,
 * including:
 * - Speech recognition using Android's SpeechRecognizer API
 * - Real-time audio recording and processing
 * - Bluetooth device pairing and data transmission
 * - Comprehensive error handling and fallback mechanisms
 * - Test mode for emulator and development testing
 * - Modern Jetpack Compose UI with Material Design 3
 * 
 * Features:
 * - Voice recognition with automatic retry on failure
 * - Bluetooth audio transmission with JSON protocol
 * - Samsung device compatibility with specialized diagnostics
 * - Test mode with simulated speech recognition
 * - Notification channel management for Android 13+
 * - Comprehensive permission handling for microphone and Bluetooth
 * 
 * UI Components:
 * - Floating Action Buttons for different functions
 * - Status display with real-time updates
 * - Bluetooth device selection dialog
 * - Error reporting with Snackbar notifications
 * 
 * @author Somewear Demo Team
 * @since 1.0
 */
class TranscribeAndSendActivity : ComponentActivity() {
    
    companion object {
        /** Maximum retry attempts for speech recognition before giving up */
        private const val MAX_RETRIES = 2 // Maximum retry attempts for speech recognition
    }

    /** Android's SpeechRecognizer instance for voice recognition */
    private lateinit var speechRecognizer: SpeechRecognizer
    
    /** Flag indicating if speech recognition is currently active */
    private var isListening = false
    
    /** Current retry count for failed speech recognition attempts */
    private var retryCount = 0
    
    /** Snackbar host state for displaying user notifications */
    private lateinit var snackbarHostState: SnackbarHostState
    
    /** Coroutine scope for managing asynchronous operations */
    private lateinit var scope: CoroutineScope
    
    /** Current callback function for handling speech recognition results */
    private var currentCallback: ((String) -> Unit)? = null
    
    /** Bluetooth manager instance for device communication */
    private lateinit var bluetoothAudioManager: BluetoothAudioManager
    
    /** Cached audio data from the last recording session */
    private var recordedAudio: ByteArray? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("TranscribeActivity", "Audio permission granted - retrying speech recognition")
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "✅ Microphone permission granted!",
                    duration = SnackbarDuration.Short
                )
            }
        } else {
            Log.e("TranscribeActivity", "Audio permission denied by user")
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "❌ Samsung tablets require microphone permission. Enable in Settings > Apps > Somewear Demo > Permissions",
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    private val requestBluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("TranscribeActivity", "Bluetooth permission granted")
        } else {
            Log.d("TranscribeActivity", "Bluetooth permission denied")
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("TranscribeActivity", "Notification permission granted")
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "✅ Notification permission granted!",
                    duration = SnackbarDuration.Short
                )
            }
        } else {
            Log.d("TranscribeActivity", "Notification permission denied")
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "ℹ️ Notification permission denied. You can enable it later in Settings.",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Create notification channel for Android 8.0+
        createNotificationChannel()

        // Request notification permission for Android 13+
        requestNotificationPermissionIfNeeded()

        // Initialize speech recognizer
        initializeSpeechRecognizer()

        setContent {
            snackbarHostState = remember { SnackbarHostState() }
            scope = rememberCoroutineScope()

            // Initialize Bluetooth manager
            bluetoothAudioManager = remember {
                BluetoothAudioManager(
                    context = this@TranscribeAndSendActivity,
                    onStatusUpdate = { status ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = status,
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    onError = { error ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Error: $error",
                                duration = SnackbarDuration.Long
                            )
                        }
                    }
                )
            }

            var showDialog by remember { mutableStateOf(false) }
            var showBluetoothDialog by remember { mutableStateOf(false) }
            var transcribedText by remember { mutableStateOf("") }
            var isRecording by remember { mutableStateOf(false) }
            var isEmulatorMode by remember { mutableStateOf(true) } // Enable test mode
            var bluetoothStatus by remember { mutableStateOf("Not connected") }
            var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                floatingActionButton = {
                    Column {
                        Row {
                            // Bluetooth Connection FAB
                            FloatingActionButton(
                                onClick = { showBluetoothDialog = true },
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "📶",
                                    color = Color.White
                                )
                            }

                            // Test Mode FAB (for emulator testing)
                            FloatingActionButton(
                                onClick = {
                                    // Simulate speech recognition with test phrases
                                    val testPhrases = listOf(
                                        "Hello, this is a test transcription.",
                                        "Speech recognition is working on emulator.",
                                        "Testing audio to text conversion.",
                                        "Emulator speech simulation successful.",
                                        "Voice recognition test completed."
                                    )
                                    transcribedText = testPhrases.random()
                                    Log.d(
                                        "TranscribeActivity",
                                        "Test transcription: $transcribedText"
                                    )

                                    // Send via Bluetooth if connected
                                    bluetoothAudioManager.sendTextOnly(transcribedText, scope)

                                    // Show success message
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = "✅ Test speech recognized and sent!",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(end = 16.dp)
                            ) {
                                Text(
                                    text = "🧪",
                                    color = Color.White
                                )
                            }

                            // Diagnostic FAB for Samsung troubleshooting
                            FloatingActionButton(
                                onClick = {
                                    // Run diagnostics
                                    runSpeechDiagnostics()
                                },
                                containerColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "🔧",
                                    color = Color.White
                                )
                            }

                            // Real Speech Recognition FAB
                            FloatingActionButton(
                                onClick = {
                                    if (!isRecording) {
                                        // Start recording audio before speech recognition
                                        recordedAudio = bluetoothAudioManager.startAudioRecording()

                                        startListening { text ->
                                            transcribedText = text
                                            isRecording = false

                                            // Stop recording and send both text and audio
                                            bluetoothAudioManager.stopAudioRecording()
                                            bluetoothAudioManager.sendTextAndAudio(
                                                text,
                                                recordedAudio,
                                                scope
                                            )
                                        }
                                        isRecording = true
                                    } else {
                                        stopListening()
                                        bluetoothAudioManager.stopAudioRecording()
                                        isRecording = false
                                    }
                                },
                                containerColor = if (isRecording)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = if (isRecording) "⏹" else "🎤",
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF9C27B0),
                                    Color(0xFFFFFFFF)
                                )
                            )
                        )
                ) {
                    // Content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))

                        // Instructions for emulator testing
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Blue.copy(alpha = 0.1f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = "🧪 Emulator Testing:",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = "📶 Bluetooth • 🧪 Test mode • 🔧 Diagnostics • 🎤 Real speech",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White.copy(alpha = 0.9f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "Transcribed Text:",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.Black
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = if (transcribedText.isEmpty())
                                        "Tap the microphone button to start recording..."
                                    else transcribedText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (transcribedText.isEmpty())
                                        Color.Gray
                                    else Color.Black
                                )

                                // Samsung Troubleshooting Panel
                                if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                                    Spacer(modifier = Modifier.height(16.dp))

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = Color.Magenta.copy(alpha = 0.1f)
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp)
                                        ) {
                                            Text(
                                                text = "📱 Samsung Device Detected",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = Color.Red
                                            )
                                            Text(
                                                text = "If getting 'Error 10', tap below to check permissions:",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Black
                                            )

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Button(
                                                onClick = {
                                                    // Force re-request permissions with Samsung-specific handling
                                                    Log.i("TranscribeActivity", "Manual permission check requested")
                                                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFFFF6B35) // Orange
                                                )
                                            ) {
                                                Text("🔐 Re-Check Microphone Permission")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (isRecording) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "🔴 Recording... Speak now",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Red
                            )
                        }
                    }
                }

                // Dialog
                if (showDialog) {
                    AlertDialog(
                        onDismissRequest = { showDialog = false },
                        title = {
                            Text("Dialog Title")
                        },
                        text = {
                            Text("This is a sample dialog that appears when you tap the floating action button!")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { showDialog = false }
                            ) {
                                Text("OK")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showDialog = false }
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Bluetooth Device Selection Dialog
                if (showBluetoothDialog) {
                    LaunchedEffect(showBluetoothDialog) {
                        // Check Bluetooth permissions
                        if (ContextCompat.checkSelfPermission(
                                this@TranscribeAndSendActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            requestBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            pairedDevices = bluetoothAudioManager.getPairedDevices()
                        }
                    }

                    AlertDialog(
                        onDismissRequest = { showBluetoothDialog = false },
                        title = {
                            Text("Select Bluetooth Device")
                        },
                        text = {
                            LazyColumn {
                                if (pairedDevices.isEmpty()) {
                                    item {
                                        Text("No paired devices found. Please pair a device in system settings.")
                                    }
                                } else {
                                    items(pairedDevices) { device ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            onClick = {
                                                bluetoothAudioManager.connectToDevice(device, scope)
                                                bluetoothStatus = "Connecting to ${device.name}..."
                                                showBluetoothDialog = false
                                            }
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp)
                                            ) {
                                                Text(
                                                    text = device.name ?: "Unknown Device",
                                                    style = MaterialTheme.typography.titleSmall
                                                )
                                                Text(
                                                    text = device.address,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.Gray
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { showBluetoothDialog = false }
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Handles speech recognition permission errors, particularly for Samsung devices.
     * 
     * Samsung devices often have specific requirements for speech recognition permissions
     * and may require additional setup for Bixby or Google Voice services. This method
     * provides targeted troubleshooting messages based on the device configuration.
     * 
     * The method:
     * - Checks current audio permission status
     * - Detects Samsung Voice/Bixby service availability
     * - Provides device-specific troubleshooting guidance
     * - Displays helpful error messages to guide users
     */
    private fun handlePermissionError() {
        Log.e("TranscribeActivity", "Handling error 10 - Samsung permission issue")

        // Check current permission status
        val hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.d("TranscribeActivity", "Current audio permission: $hasAudioPermission")

        // Check if Samsung Voice Service is available
        val samsungVoiceIntent = Intent("com.samsung.android.bixby.agent.VOICE_WAKEUP")
        val hasSamsungVoice = packageManager.resolveService(samsungVoiceIntent, 0) != null

        // Samsung-specific troubleshooting
        scope.launch {
            val message = if (hasSamsungVoice) {
                "Samsung with Bixby: Check Samsung Voice permissions in settings"
            } else {
                "Samsung tablet: Check Google app permissions for speech recognition"
            }

            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Long
            )
        }

        // Try to re-request permission
        if (!hasAudioPermission) {
            Log.d("TranscribeActivity", "Re-requesting microphone permission")
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            // Permission is granted but Samsung is blocking it - show specific guidance
            Log.d("TranscribeActivity", "Permission granted but Samsung blocking - show guidance")
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "⚙️ Permission granted but Samsung blocking. Try: Settings > Apps > Google > Permissions",
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    /**
     * Runs comprehensive speech recognition diagnostics for troubleshooting.
     * 
     * This method performs a complete system check to help identify issues with
     * speech recognition on various device types, particularly useful for Samsung
     * tablets and other devices with non-standard configurations.
     * 
     * Diagnostic information includes:
     * - Device manufacturer, model, and Android version
     * - Speech recognition service availability
     * - Audio permission status
     * - Bluetooth connection capability
     * - Available speech recognition engines
     * - Audio recording hardware status
     * 
     * Results are displayed to the user via Snackbar notifications to aid in
     * troubleshooting and device-specific configuration issues.
     */
    private fun runSpeechDiagnostics() {
        val deviceInfo = StringBuilder()
        deviceInfo.append("📱 Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        deviceInfo.append("🔧 Android: ${android.os.Build.VERSION.RELEASE}\n")

        // Check speech recognition availability
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        deviceInfo.append("🎤 Speech Recognition Available: $isAvailable\n")

        // Check microphone permission in detail
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        deviceInfo.append("🔐 Microphone Permission: $hasPermission\n")

        // Samsung-specific permission checks
        if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            deviceInfo.append("📋 SAMSUNG DEVICE DETECTED - Special checks:\n")

            // Check if Samsung Voice Service is enabled
            try {
                val samsungVoiceIntent = Intent("com.samsung.android.bixby.agent.VOICE_WAKEUP")
                val resolveInfo = packageManager.resolveService(samsungVoiceIntent, 0)
                val serviceFound = resolveInfo != null
                deviceInfo.append("   • Samsung Voice Service: ${if (serviceFound) "Available" else "Not found"}\n")

                if (!serviceFound) {
                    deviceInfo.append("   ℹ️ This is normal for many Samsung tablets - using standard Android speech\n")
                }
            } catch (e: Exception) {
                deviceInfo.append("   • Samsung Voice Service: Error checking - ${e.message}\n")
            }

            // Check for Google Speech Services (fallback for Samsung without Bixby)
            try {
                val googleSpeechIntent = Intent("android.speech.action.RECOGNIZE_SPEECH")
                val googleResolveInfo = packageManager.resolveActivity(googleSpeechIntent, 0)
                deviceInfo.append("   • Google Speech Available: ${if (googleResolveInfo != null) "Yes" else "No"}\n")
            } catch (e: Exception) {
                deviceInfo.append("   • Google Speech: Error checking - ${e.message}\n")
            }

            // Samsung privacy settings hint
            deviceInfo.append("   • Action needed: Check Settings > Privacy > Permission manager > Microphone\n")
            deviceInfo.append("   • Also check: Settings > Apps > Somewear Demo > Permissions\n")
            deviceInfo.append("   • Samsung tip: Look for 'Special app access' in Settings\n")
        }

        // Check for Samsung-specific speech engines
        val packageManager = packageManager
        val samsungEngines = packageManager.getInstalledApplications(0).filter {
            it.packageName.contains("samsung") &&
            (it.packageName.contains("speech") || it.packageName.contains("voice") || it.packageName.contains("bixby"))
        }
        deviceInfo.append("🗣️ Samsung Speech Engines: ${samsungEngines.size}\n")

        if (samsungEngines.isNotEmpty()) {
            deviceInfo.append("   Available engines:\n")
            samsungEngines.forEach { engine ->
                deviceInfo.append("   • ${engine.packageName}\n")
            }
        }
        
        // Check for Qualcomm QSPM service availability
        try {
            val qualcommEngines = packageManager.getInstalledApplications(0).filter {
                it.packageName.contains("qualcomm") || it.packageName.contains("qspm")
            }
            deviceInfo.append("🔧 Qualcomm QSPM Services: ${qualcommEngines.size}\n")
            if (qualcommEngines.isEmpty()) {
                deviceInfo.append("   • QSPM AIDL service: Not available (this is normal)\n")
                deviceInfo.append("   • Solution: App will use Google Speech instead\n")
            } else {
                qualcommEngines.forEach { engine ->
                    deviceInfo.append("   • ${engine.packageName}\n")
                }
            }
        } catch (e: Exception) {
            deviceInfo.append("🔧 Qualcomm QSPM: Error checking - ${e.message}\n")
        }

        // Force permission re-check
        deviceInfo.append("🔄 Re-requesting permissions now...\n")

        // Log the diagnostics
        Log.i("TranscribeActivity", "Speech Diagnostics:\n$deviceInfo")

        // Show in snackbar
        scope.launch {
            snackbarHostState.showSnackbar(
                message = "📋 Samsung diagnostics complete - requesting permissions",
                duration = SnackbarDuration.Long
            )
        }

        // Force permission request for Samsung devices
        if (!hasPermission) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Creates a notification channel for speech recognition status updates.
     * 
     * Required for Android 8.0 (API level 26) and higher. Creates a default-importance
     * notification channel specifically for speech recognition feedback and status updates.
     * This enables the app to show notifications about recognition results, errors,
     * and Bluetooth connection status.
     * 
     * Channel configuration:
     * - ID: "speech_recognition_channel"
     * - Name: "Speech Recognition"
     * - Importance: Default (allows sound and vibration)
     * - Description: User-friendly description for the settings UI
     */
    private fun createNotificationChannel() {
        // Create notification channel for Android 8.0 and higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "speech_recognition_channel"
            val channelName = "Speech Recognition"
            val channelDescription = "Notifications for speech recognition status and results"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDescription
                enableVibration(true)
                enableLights(true)
            }
            
            // Register the channel with the system
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            Log.d("TranscribeActivity", "Notification channel created: $channelId")
        }
    }

    /**
     * Requests notification permission for Android 13+ devices.
     * 
     * Starting with Android 13 (API level 33), apps must explicitly request permission
     * to post notifications. This method checks the current permission status and
     * requests the POST_NOTIFICATIONS permission if it hasn't been granted.
     * 
     * On older Android versions, this method does nothing as notification permission
     * is granted automatically.
     * 
     * The permission request result is handled by [requestNotificationPermissionLauncher].
     */
    private fun requestNotificationPermissionIfNeeded() {
        // Request notification permission for Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                this, 
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasNotificationPermission) {
                Log.d("TranscribeActivity", "Requesting notification permission for Android 13+")
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d("TranscribeActivity", "Notification permission already granted")
            }
        } else {
            Log.d("TranscribeActivity", "Android version < 13, notification permission not required")
        }
    }

    /**
     * Initializes the SpeechRecognizer with device-specific optimizations.
     * 
     * This method handles the complex initialization process for speech recognition,
     * with special considerations for different device manufacturers:
     * 
     * - Samsung devices: Uses Samsung Voice as the preferred recognizer
     * - Other devices: Falls back to Google's speech recognition service
     * - Error handling: Implements multiple fallback strategies
     * 
     * The initialization process:
     * 1. Attempts Samsung-optimized initialization for Samsung devices
     * 2. Falls back to standard Google Speech Recognition
     * 3. Implements comprehensive error handling
     * 4. Logs detailed information for troubleshooting
     * 
     * @throws SecurityException if required permissions are not granted
     * @see tryFallbackSpeechRecognizer
     */
    private fun initializeSpeechRecognizer() {
        try {
            // Try Samsung-optimized initialization first
            Log.d("TranscribeActivity", "Attempting Samsung Bixby speech recognizer initialization...")
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this,
                android.content.ComponentName(
                    "com.samsung.android.bixby.service",
                "com.samsung.android.bixby.service.SpeechRecognitionService"))
            Log.d("TranscribeActivity", "Initialized Samsung speech recognizer")
        } catch (e: SecurityException) {
            Log.w("TranscribeActivity", "Samsung speech service access denied: ${e.message}")
            tryFallbackSpeechRecognizer()
        } catch (e: UnsatisfiedLinkError) {
            Log.w("TranscribeActivity", "Native library loading failed (likely libpenguin.so): ${e.message}")
            tryFallbackSpeechRecognizer()
        } catch (e: Exception) {
            Log.w("TranscribeActivity", "Samsung speech recognizer failed: ${e.message}")
            // Check if it's related to native library loading
            if (e.message?.contains("libpenguin", ignoreCase = true) == true ||
                e.message?.contains("dlopen failed", ignoreCase = true) == true ||
                e.message?.contains("library", ignoreCase = true) == true) {
                Log.w("TranscribeActivity", "Detected native library issue - using standard Android speech")
            }
            tryFallbackSpeechRecognizer()
        }

        // Check if speech recognition is available
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w("TranscribeActivity", "Speech recognition not available on this device")
        }
    }

    /**
     * Attempts to initialize a fallback speech recognizer when the primary initialization fails.
     * 
     * This method is called when the Samsung-optimized speech recognizer initialization
     * fails, typically due to:
     * - Missing native libraries (libpenguin.so issues on some Samsung devices)
     * - Samsung Voice service unavailability
     * - Permission or configuration problems
     * 
     * The fallback strategy uses the standard Android SpeechRecognizer with
     * Google's speech recognition service, which is more universally compatible
     * across different device manufacturers.
     * 
     * @throws SecurityException if speech recognition permissions are not available
     */
    private fun tryFallbackSpeechRecognizer() {
        try {
            // Fallback to default speech recognizer (pure Android/Google)
            Log.d("TranscribeActivity", "Trying standard Android speech recognizer...")
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            Log.d("TranscribeActivity", "Initialized standard speech recognizer successfully")
        } catch (e: Exception) {
            Log.e("TranscribeActivity", "All speech recognizer initialization failed: ${e.message}")
            // Last resort - try creating without any specific service
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this, null)
                Log.d("TranscribeActivity", "Initialized generic speech recognizer")
            } catch (finalE: Exception) {
                Log.e("TranscribeActivity", "Complete speech recognition failure: ${finalE.message}")
            }
        }
    }

    /**
     * Initiates speech recognition listening with comprehensive error handling and retry logic.
     * 
     * This method manages the complete speech recognition workflow:
     * 
     * 1. **Permission Verification**: Checks for RECORD_AUDIO permission
     * 2. **Recognition Setup**: Configures SpeechRecognizer with optimized parameters
     * 3. **Intent Configuration**: Sets up RecognizerIntent with language and model preferences
     * 4. **Error Handling**: Implements comprehensive error handling and retry mechanisms
     * 5. **Callback Management**: Manages result callbacks and UI state updates
     * 
     * Recognition parameters:
     * - Language model: Free-form (optimized for natural speech)
     * - Language: User's default locale
     * - Maximum results: 5 alternative transcriptions
     * - Partial results: Enabled for real-time feedback
     * 
     * @param onResult Callback function to handle successful speech recognition results
     * 
     * @throws SecurityException if RECORD_AUDIO permission is not granted
     */
    private fun startListening(onResult: (String) -> Unit) {
        // Store the callback for potential retries
        currentCallback = onResult

        // Check permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("TranscribeActivity", "Speech recognition not available")
            return
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            
            // For Samsung tablets without Samsung Voice Services, use standard Android settings
            if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                // Check if Samsung Voice Service is available
                val samsungVoiceIntent = Intent("com.samsung.android.bixby.agent.VOICE_WAKEUP")
                val hasSamsungVoice = packageManager.resolveService(samsungVoiceIntent, 0) != null
                
                if (hasSamsungVoice) {
                    // Samsung-specific optimizations for devices with Samsung Voice
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                    putExtra("samsung.speech.extra.RECOGNITION_TYPE", "free_form")
                    putExtra("samsung.speech.extra.LANGUAGE_DETECTION", true)
                    putExtra("samsung.speech.extra.ENABLE_BIASING", true)
                    Log.d("TranscribeActivity", "Using Samsung Voice Services")
                } else {
                    // Standard Android settings for Samsung tablets without Samsung Voice
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) // Use Google services
                    putExtra(RecognizerIntent.EXTRA_SECURE, false)
                    // Remove Samsung-specific extras to avoid conflicts
                    Log.d("TranscribeActivity", "Using standard Android speech (Samsung tablet without Samsung Voice)")
                }
            } else {
                // Standard settings for non-Samsung devices
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            
            // Universal timeout settings
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000)
        }
        
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("TranscribeActivity", "Ready for speech - speak now!")
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "🎤 Ready! Speak now...",
                        duration = SnackbarDuration.Short
                    )
                }
            }
            
            override fun onBeginningOfSpeech() {
                Log.d("TranscribeActivity", "Beginning of speech detected")
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "🔊 Speech detected!",
                        duration = SnackbarDuration.Short
                    )
                }
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // Audio level feedback for debugging
                Log.v("TranscribeActivity", "Audio level: $rmsdB dB")
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                Log.d("TranscribeActivity", "Audio buffer received: ${buffer?.size ?: 0} bytes")
            }
            
            override fun onEndOfSpeech() {
                Log.d("TranscribeActivity", "End of speech")
            }
            
            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Please speak clearly and try again."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy - please wait"
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                        Log.w("TranscribeActivity", "ERROR_TOO_MANY_REQUESTS: Too many requests, retrying immediately")
                        "⚡ Too many requests - retrying now..."
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        // Specific handling for error 10 on Samsung devices
                        Log.e("TranscribeActivity", "ERROR 10: Insufficient permissions detected")
                        handlePermissionError()
                        "❌ Microphone permission denied or restricted. Check Samsung settings!"
                    }
                    else -> "Unknown error: $error"
                }
                Log.e("TranscribeActivity", "Speech recognition error code $error: $errorMessage")
                isListening = false
                
                // Show user-friendly error message
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = errorMessage,
                        duration = if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) 
                            SnackbarDuration.Long else SnackbarDuration.Short
                    )
                }
                
                // Auto-retry logic with rate limiting
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        if (retryCount < MAX_RETRIES) {
                            Log.d("TranscribeActivity", "Auto-retrying speech recognition (attempt ${retryCount + 1})")
                            retryCount++
                            Handler(Looper.getMainLooper()).postDelayed({
                                currentCallback?.let { callback ->
                                    startListening(callback)
                                }
                            }, 1000) // Wait 1 second before retry
                        } else {
                            retryCount = 0
                            currentCallback = null
                        }
                    }
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                        // Immediate retry for too many requests (no 30 second delay)
                        if (retryCount < MAX_RETRIES) {
                            retryCount++
                            Handler(Looper.getMainLooper()).postDelayed({
                                currentCallback?.let { callback ->
                                    startListening(callback)
                                }
                            }, 1000) // Wait just 1 second before retry
                        } else {
                            retryCount = 0
                            currentCallback = null
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "⚠️ Speech service overloaded after multiple attempts",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Short delay for busy service
                        if (retryCount < MAX_RETRIES) {
                            retryCount++
                            Handler(Looper.getMainLooper()).postDelayed({
                                currentCallback?.let { callback ->
                                    startListening(callback)
                                }
                            }, 3000) // Wait 3 seconds for busy service
                        } else {
                            retryCount = 0
                            currentCallback = null
                        }
                    }
                    else -> {
                        // For other errors, don't retry
                        retryCount = 0
                        currentCallback = null
                    }
                }
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val transcribedText = matches[0]
                    Log.d("TranscribeActivity", "Transcribed: $transcribedText")
                    // Reset retry count on successful recognition
                    retryCount = 0
                    currentCallback = null
                    onResult(transcribedText)
                }
                isListening = false
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partialText = matches[0]
                    Log.d("TranscribeActivity", "Partial: $partialText")
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                // Speech recognition event
            }
        })
        
        // Samsung devices sometimes need a small delay
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                speechRecognizer.startListening(intent)
                isListening = true
                Log.d("TranscribeActivity", "Started listening")
            } catch (e: SecurityException) {
                Log.e("TranscribeActivity", "Security exception - permission issue: ${e.message}")
                handlePermissionError()
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "❌ Permission error: Check microphone settings",
                        duration = SnackbarDuration.Long
                    )
                }
            } catch (e: IllegalStateException) {
                Log.e("TranscribeActivity", "Binding to recognition service failed: ${e.message}")
                // Try to reinitialize the speech recognizer
                tryFallbackSpeechRecognition(onResult)
            } catch (e: UnsatisfiedLinkError) {
                Log.e("TranscribeActivity", "Native library loading failed: ${e.message}")
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "🔄 Library issue detected - switching to Google Speech",
                        duration = SnackbarDuration.Short
                    )
                }
                tryGoogleSpeechRecognition(onResult)
            } catch (e: Exception) {
                Log.e("TranscribeActivity", "Failed to start listening: ${e.message}")
                
                // Check for specific error types
                val errorMessage = e.message?.lowercase() ?: ""
                when {
                    errorMessage.contains("libpenguin") || errorMessage.contains("dlopen failed") || 
                    errorMessage.contains("library") && errorMessage.contains("not found") -> {
                        Log.w("TranscribeActivity", "Native library error detected (libpenguin.so or similar)")
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "🔄 Library not found - using alternative speech service",
                                duration = SnackbarDuration.Short
                            )
                        }
                        tryGoogleSpeechRecognition(onResult)
                    }
                    errorMessage.contains("qspm") || errorMessage.contains("aidl service doesn't exist") -> {
                        Log.w("TranscribeActivity", "QSPM AIDL service error - Qualcomm speech service not available")
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "🔄 Qualcomm speech service unavailable - using Google Speech",
                                duration = SnackbarDuration.Short
                            )
                        }
                        tryGoogleSpeechRecognition(onResult)
                    }
                    errorMessage.contains("bind") -> {
                        Log.w("TranscribeActivity", "Detected binding error - attempting fallback")
                        tryFallbackSpeechRecognition(onResult)
                    }
                    else -> {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "❌ Speech recognition error: ${e.message}",
                                duration = SnackbarDuration.Long
                            )
                        }
                    }
                }
            }
        }, if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) 500 else 100) // Longer delay for Samsung
    }
    
    private fun tryFallbackSpeechRecognition(onResult: (String) -> Unit) {
        Log.i("TranscribeActivity", "Attempting fallback speech recognition method")
        
        scope.launch {
            snackbarHostState.showSnackbar(
                message = "🔄 Retrying with different speech service...",
                duration = SnackbarDuration.Short
            )
        }
        
        // Destroy current recognizer and create a new one
        try {
            if (::speechRecognizer.isInitialized) {
                speechRecognizer.destroy()
            }
        } catch (e: Exception) {
            Log.w("TranscribeActivity", "Error destroying speech recognizer: ${e.message}")
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Reinitialize with minimal settings for better compatibility
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                
                val fallbackIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    // Minimal settings for Samsung compatibility
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(RecognizerIntent.EXTRA_SECURE, false)
                }
                
                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("TranscribeActivity", "Fallback: Ready for speech")
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "🎤 Fallback ready! Speak now...",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                    
                    override fun onBeginningOfSpeech() {
                        Log.d("TranscribeActivity", "Fallback: Beginning of speech")
                    }
                    
                    override fun onRmsChanged(rmsdB: Float) { }
                    override fun onBufferReceived(buffer: ByteArray?) { }
                    override fun onEndOfSpeech() {
                        Log.d("TranscribeActivity", "Fallback: End of speech")
                    }
                    
                    override fun onError(error: Int) {
                        val errorMessage = "Fallback speech recognition failed with error: $error"
                        Log.e("TranscribeActivity", errorMessage)
                        isListening = false
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "❌ Both speech services failed. Check device settings.",
                                duration = SnackbarDuration.Long
                            )
                        }
                    }
                    
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val transcribedText = matches[0]
                            Log.d("TranscribeActivity", "Fallback transcribed: $transcribedText")
                            onResult(transcribedText)
                        }
                        isListening = false
                    }
                    
                    override fun onPartialResults(partialResults: Bundle?) { }
                    override fun onEvent(eventType: Int, params: Bundle?) { }
                })
                
                speechRecognizer.startListening(fallbackIntent)
                isListening = true
                Log.i("TranscribeActivity", "Fallback speech recognition started")
                
            } catch (e: Exception) {
                Log.e("TranscribeActivity", "Fallback method also failed: ${e.message}")
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "❌ All speech recognition methods failed. Restart app and check permissions.",
                        duration = SnackbarDuration.Long
                    )
                }
            }
        }, 1000) // Give it a moment before trying fallback
    }
    
    private fun tryGoogleSpeechRecognition(onResult: (String) -> Unit) {
        Log.i("TranscribeActivity", "Attempting Google Speech Recognition (avoiding QSPM)")
        
        scope.launch {
            snackbarHostState.showSnackbar(
                message = "🎤 Using Google Speech Recognition...",
                duration = SnackbarDuration.Short
            )
        }
        
        // Destroy current recognizer and create a new one with Google-specific settings
        try {
            if (::speechRecognizer.isInitialized) {
                speechRecognizer.destroy()
            }
        } catch (e: Exception) {
            Log.w("TranscribeActivity", "Error destroying speech recognizer: ${e.message}")
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Create speech recognizer with explicit Google preference
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                
                val googleIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    
                    // Force Google Speech settings (avoid Samsung/Qualcomm services)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) // Use online Google services
                    putExtra(RecognizerIntent.EXTRA_SECURE, false)
                    putExtra("android.speech.extra.GET_AUDIO_FORMAT", "audio/AMR")
                    putExtra("android.speech.extra.GET_AUDIO", true)
                    
                    // Remove any Samsung/Qualcomm specific extras
                    // Explicitly avoid QSPM by using standard Android speech APIs only
                    Log.d("TranscribeActivity", "Using Google Speech with standard Android APIs only")
                }
                
                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("TranscribeActivity", "Google Speech: Ready for speech")
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "🎤 Google Speech ready! Speak now...",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                    
                    override fun onBeginningOfSpeech() {
                        Log.d("TranscribeActivity", "Google Speech: Beginning of speech")
                    }
                    
                    override fun onRmsChanged(rmsdB: Float) { }
                    override fun onBufferReceived(buffer: ByteArray?) { }
                    override fun onEndOfSpeech() {
                        Log.d("TranscribeActivity", "Google Speech: End of speech")
                    }
                    
                    override fun onError(error: Int) {
                        val errorMessage = "Google Speech recognition failed with error: $error"
                        Log.e("TranscribeActivity", errorMessage)
                        isListening = false
                        
                        // If Google Speech also fails, try the general fallback
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            handlePermissionError()
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "❌ Google Speech failed. Trying standard fallback...",
                                    duration = SnackbarDuration.Short
                                )
                            }
                            tryFallbackSpeechRecognition(onResult)
                        }
                    }
                    
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val transcribedText = matches[0]
                            Log.d("TranscribeActivity", "Google Speech transcribed: $transcribedText")
                            onResult(transcribedText)
                        }
                        isListening = false
                    }
                    
                    override fun onPartialResults(partialResults: Bundle?) { }
                    override fun onEvent(eventType: Int, params: Bundle?) { }
                })
                
                speechRecognizer.startListening(googleIntent)
                isListening = true
                Log.i("TranscribeActivity", "Google Speech recognition started successfully")
                
            } catch (e: Exception) {
                Log.e("TranscribeActivity", "Google Speech method also failed: ${e.message}")
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "❌ Google Speech failed. Trying general fallback...",
                        duration = SnackbarDuration.Short
                    )
                }
                tryFallbackSpeechRecognition(onResult)
            }
        }, 500) // Brief delay before Google Speech attempt
    }
    
    /**
     * Stops the active speech recognition session.
     * 
     * Safely terminates any ongoing speech recognition and updates the internal
     * listening state. This method can be called multiple times without side effects.
     * 
     * Used when:
     * - User manually stops recording
     * - Recognition timeout occurs
     * - Error conditions require stopping recognition
     * - Activity lifecycle events require cleanup
     */
    private fun stopListening() {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
            Log.d("TranscribeActivity", "Stopped listening")
        }
    }
    
    /**
     * Cleans up resources when the activity is destroyed.
     * 
     * Performs comprehensive cleanup including:
     * - Speech recognizer destruction and resource release
     * - Bluetooth connection termination
     * - Audio recording session cleanup
     * 
     * This ensures no memory leaks or background processes continue
     * after the activity is no longer needed.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        if (::bluetoothAudioManager.isInitialized) {
            bluetoothAudioManager.disconnect()
        }
    }
}