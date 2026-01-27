package com.example.somewear_demo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.*

/**
 * Manages Bluetooth connectivity and audio recording for the Somewear demo application.
 * 
 * This class handles Bluetooth device pairing, connection establishment using SPP (Serial Port Profile),
 * audio recording from the device microphone, and transmission of both text and audio data
 * over the Bluetooth connection.
 * 
 * Features:
 * - Bluetooth device discovery and connection management
 * - Real-time audio recording with configurable parameters
 * - JSON-based data transmission protocol
 * - Comprehensive error handling and status reporting
 * - Coroutine-based asynchronous operations for non-blocking UI
 * 
 * @param context The application context for accessing system services and permissions
 * @param onStatusUpdate Optional callback function for receiving status updates and notifications
 * @param onError Optional callback function for receiving error messages and handling failures
 * 
 * @author Somewear Demo Team
 * @since 1.0
 */
class BluetoothAudioManager(
    private val context: Context,
    private val onStatusUpdate: ((String) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) {
    
    /** Bluetooth adapter for device communication */
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    
    /** Active Bluetooth socket for RFCOMM connection */
    private var bluetoothSocket: BluetoothSocket? = null
    
    /** Output stream for sending data over Bluetooth */
    private var outputStream: OutputStream? = null
    
    /** AudioRecord instance for capturing microphone input */
    private var audioRecord: AudioRecord? = null
    
    /** Flag indicating if audio recording is currently active */
    private var isRecording = false
    
    // Audio recording parameters
    /** Sample rate for audio recording in Hz */
    private val sampleRate = 44100
    
    /** Audio channel configuration (mono) */
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    
    /** Audio encoding format (16-bit PCM) */
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    /** Minimum buffer size required for audio recording */
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // SPP UUID for serial communication
    /** UUID for Serial Port Profile (SPP) Bluetooth connection */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    
    /**
     * Checks if there is an active Bluetooth connection.
     * 
     * @return true if the Bluetooth socket is connected, false otherwise
     */
    fun isConnected(): Boolean {
        return bluetoothSocket?.isConnected == true
    }
    
    /**
     * Retrieves a list of paired Bluetooth devices.
     * 
     * Requires BLUETOOTH_CONNECT permission. If permission is not granted,
     * returns an empty list.
     * 
     * @return List of paired BluetoothDevice objects, or empty list if no devices are paired or permission is denied
     */
    fun getPairedDevices(): List<BluetoothDevice> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }
    
    /**
     * Establishes a Bluetooth connection to the specified device.
     * 
     * Creates an RFCOMM socket connection using the Serial Port Profile (SPP) UUID.
     * The connection process is performed asynchronously on the IO dispatcher to avoid
     * blocking the main thread. Status updates and errors are reported through the
     * configured callback functions.
     * 
     * @param device The BluetoothDevice to connect to
     * @param scope CoroutineScope for managing the asynchronous connection operation
     * 
     * @throws SecurityException if BLUETOOTH_CONNECT permission is not granted
     * @throws IOException if the connection fails due to network issues
     */
    fun connectToDevice(device: BluetoothDevice, scope: CoroutineScope) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            val errorMsg = "Missing BLUETOOTH_CONNECT permission"
            Log.e("BluetoothManager", errorMsg)
            onError?.invoke(errorMsg)
            return
        }
        
        onStatusUpdate?.invoke("Connecting to ${device.name}...")
        
        scope.launch(Dispatchers.IO) {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                
                val successMsg = "Connected to ${device.name}"
                Log.i("BluetoothManager", successMsg)
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke(successMsg)
                }
            } catch (e: IOException) {
                val errorMsg = "Connection failed: ${e.message}"
                Log.e("BluetoothManager", errorMsg)
                bluetoothSocket = null
                outputStream = null
                withContext(Dispatchers.Main) {
                    onError?.invoke(errorMsg)
                }
            }
        }
    }
    
    /**
     * Initiates Bluetooth device discovery process.
     * 
     * This is a placeholder implementation that currently only logs the discovery intent.
     * In a full implementation, this would start the device discovery process and handle
     * discovered devices through broadcast receivers.
     * 
     * @see BluetoothAdapter.startDiscovery
     */
    fun discoverAndConnect() {
        // For simplicity, this method just logs that discovery would happen
        Log.i("BluetoothManager", "Would start device discovery here")
    }
    
    /**
     * Disconnects from the current Bluetooth device and releases all resources.
     * 
     * Safely closes the Bluetooth socket and output stream, handling any IOException
     * that might occur during the closing process. All connection-related variables
     * are reset to null after disconnection.
     * 
     * This method is safe to call multiple times and will not throw exceptions.
     */
    fun disconnect() {
        try {
            bluetoothSocket?.close()
            outputStream?.close()
        } catch (e: IOException) {
            Log.e("BluetoothManager", "Error closing connection: ${e.message}")
        } finally {
            bluetoothSocket = null
            outputStream = null
        }
    }
    
    /**
     * Starts audio recording from the device microphone and captures a single buffer of audio data.
     * 
     * Initializes an AudioRecord instance with predefined parameters (44.1kHz, mono, 16-bit PCM)
     * and captures one buffer of audio data. The recording is started and immediately stopped
     * after reading one buffer, making this suitable for short audio captures.
     * 
     * Audio parameters:
     * - Sample rate: 44,100 Hz
     * - Channels: Mono (single channel)
     * - Encoding: 16-bit PCM
     * - Buffer size: System-determined minimum buffer size
     * 
     * @return ByteArray containing the recorded audio data, or null if recording failed
     * 
     * @throws SecurityException if RECORD_AUDIO permission is not granted
     * @throws IllegalStateException if AudioRecord initialization fails
     */
    fun startAudioRecording(): ByteArray? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val errorMsg = "Missing RECORD_AUDIO permission"
            Log.e("BluetoothManager", errorMsg)
            onError?.invoke(errorMsg)
            return null
        }
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val errorMsg = "AudioRecord initialization failed"
                Log.e("BluetoothManager", errorMsg)
                onError?.invoke(errorMsg)
                return null
            }
            
            audioRecord?.startRecording()
            isRecording = true
            
            val audioBuffer = ByteArray(bufferSize)
            val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
            
            Log.d("BluetoothManager", "Recorded $bytesRead bytes of audio")
            onStatusUpdate?.invoke("Recorded $bytesRead bytes of audio")
            return if (bytesRead > 0) audioBuffer.copyOf(bytesRead) else null
            
        } catch (e: Exception) {
            val errorMsg = "Audio recording error: ${e.message}"
            Log.e("BluetoothManager", errorMsg)
            onError?.invoke(errorMsg)
            return null
        }
    }
    
    /**
     * Stops the current audio recording session and releases AudioRecord resources.
     * 
     * Safely stops any active recording, releases the AudioRecord instance, and resets
     * the recording state. This method is safe to call multiple times and will not
     * throw exceptions if no recording is active.
     * 
     * Should be called after [startAudioRecording] to properly clean up resources.
     */
    fun stopAudioRecording() {
        if (isRecording) {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            isRecording = false
            Log.d("BluetoothManager", "Audio recording stopped")
        }
    }
    
    /**
     * Sends both text and audio data over the Bluetooth connection as a structured JSON message.
     * 
     * Creates a JSON message containing:
     * - Message type: "speech_data"
     * - Timestamp: Current system time in milliseconds
     * - Text content: The transcribed or input text
     * - Audio size: Size of the audio data in bytes
     * 
     * The JSON metadata is sent first, followed by the raw audio data if available.
     * All operations are performed asynchronously on the IO dispatcher.
     * 
     * @param text The text content to send (e.g., transcribed speech)
     * @param audioData The audio data as a ByteArray, or null if no audio is available
     * @param scope CoroutineScope for managing the asynchronous sending operation
     * 
     * @throws IOException if the Bluetooth connection is lost during transmission
     */
    fun sendTextAndAudio(text: String, audioData: ByteArray?, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val jsonData = JSONObject().apply {
                    put("type", "speech_data")
                    put("timestamp", System.currentTimeMillis())
                    put("text", text)
                    put("audio_size", audioData?.size ?: 0)
                }
                
                val message = jsonData.toString() + "\n"
                outputStream?.write(message.toByteArray())
                
                // Send audio data if available
                audioData?.let {
                    outputStream?.write(it)
                    Log.d("BluetoothManager", "Sent ${it.size} bytes of audio data")
                }
                
                outputStream?.flush()
                val successMsg = "Sent text and audio via Bluetooth"
                Log.i("BluetoothManager", successMsg)
                
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke(successMsg)
                }
                
            } catch (e: IOException) {
                val errorMsg = "Failed to send data: ${e.message}"
                Log.e("BluetoothManager", errorMsg)
                withContext(Dispatchers.Main) {
                    onError?.invoke(errorMsg)
                }
            }
        }
    }
    
    /**
     * Sends text-only data over the Bluetooth connection as a JSON message.
     * 
     * Creates a simplified JSON message containing:
     * - Message type: "text_only"
     * - Timestamp: Current system time in milliseconds
     * - Text content: The message text
     * 
     * This method is useful for sending transcribed text without accompanying audio data,
     * such as when using test modes or when audio recording is not available.
     * All operations are performed asynchronously on the IO dispatcher.
     * 
     * @param text The text content to send
     * @param scope CoroutineScope for managing the asynchronous sending operation
     * 
     * @throws IOException if the Bluetooth connection is lost during transmission
     */
    fun sendTextOnly(text: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val jsonData = JSONObject().apply {
                    put("type", "text_only")
                    put("timestamp", System.currentTimeMillis())
                    put("text", text)
                }
                
                val message = jsonData.toString() + "\n"
                outputStream?.write(message.toByteArray())
                outputStream?.flush()
                
                val successMsg = "Sent text-only via Bluetooth: $text"
                Log.i("BluetoothManager", successMsg)
                
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("Text sent successfully")
                }
                
            } catch (e: IOException) {
                val errorMsg = "Failed to send text: ${e.message}"
                Log.e("BluetoothManager", errorMsg)
                withContext(Dispatchers.Main) {
                    onError?.invoke(errorMsg)
                }
            }
        }
    }
}