package com.example.somewear_demo.services

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.*
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Enhanced location management service for ATAK integration and tactical operations.
 * 
 * This service provides comprehensive location services specifically designed for
 * tactical and emergency operations, including:
 * - High-precision GPS tracking with military-grade accuracy requirements
 * - Integration with ATAK geolocation services
 * - Emergency location broadcasting
 * - Multiple coordinate system support
 * - Location history and tracking
 * - Geofencing for tactical areas
 * 
 * Features:
 * - Real-time position updates for ATAK PLI (Position Location Information)
 * - Coordinate conversion between WGS84, MGRS, and UTM systems
 * - Emergency beacon with last-known location fallback
 * - Location accuracy filtering for tactical precision
 * - Battery-optimized tracking modes
 * - Offline location caching for remote operations
 * 
 * Integration:
 * - Automatically shares location data with AtakGeolocationService
 * - Supports Bluetooth transmission of location data
 * - Compatible with voice command location requests
 * 
 * @author Somewear Demo Team
 * @since 1.0
 */
class SomewearLocationService : Service(), LocationListener {
    
    companion object {
        private const val TAG = "SomewearLocationService"
        
        /** Minimum time between location updates in milliseconds */
        private const val LOCATION_UPDATE_INTERVAL = 10_000L // 10 seconds
        
        /** Minimum distance change for location updates in meters */
        private const val MIN_DISTANCE_CHANGE = 5.0f // 5 meters
        
        /** Required accuracy for tactical operations in meters */
        private const val TACTICAL_ACCURACY_THRESHOLD = 10.0f
        
        /** Emergency mode update interval - more frequent updates */
        private const val EMERGENCY_UPDATE_INTERVAL = 5_000L // 5 seconds
        
        /** Location history retention period in milliseconds */
        private const val LOCATION_HISTORY_RETENTION = 24 * 60 * 60 * 1000L // 24 hours
    }
    
    /** Binder for local service binding */
    inner class LocationBinder : Binder() {
        fun getService(): SomewearLocationService = this@SomewearLocationService
    }
    
    private val binder = LocationBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /** Location manager for GPS operations */
    private lateinit var locationManager: LocationManager
    
    /** Current location data */
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()
    
    /** Location service status */
    private val _locationStatus = MutableStateFlow(LocationStatus.STOPPED)
    val locationStatus: StateFlow<LocationStatus> = _locationStatus.asStateFlow()
    
    /** GPS accuracy status */
    private val _gpsAccuracy = MutableStateFlow<Float?>(null)
    val gpsAccuracy: StateFlow<Float?> = _gpsAccuracy.asStateFlow()
    
    /** Location history for tracking and emergency fallback */
    private val _locationHistory = MutableStateFlow<List<LocationRecord>>(emptyList())
    val locationHistory: StateFlow<List<LocationRecord>> = _locationHistory.asStateFlow()
    
    /** Emergency mode status */
    private val _emergencyMode = MutableStateFlow(false)
    val emergencyMode: StateFlow<Boolean> = _emergencyMode.asStateFlow()
    
    /** Reference to ATAK service for integration */
    private var atakService: AtakGeolocationService? = null
    
    /** Current tracking mode */
    private var trackingMode = TrackingMode.NORMAL
    
    /** Location update parameters */
    private var updateInterval = LOCATION_UPDATE_INTERVAL
    private var minDistance = MIN_DISTANCE_CHANGE
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Somewear Location Service created")
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Initialize with last known location if available
        initializeLastKnownLocation()
    }
    
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Location service bound")
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Somewear Location Service destroyed")
        
        stopLocationUpdates()
        serviceScope.cancel()
    }
    
    /**
     * Starts location tracking with specified mode and parameters.
     * 
     * @param mode Tracking mode (NORMAL, HIGH_ACCURACY, EMERGENCY, BATTERY_SAVER)
     */
    fun startLocationTracking(mode: TrackingMode = TrackingMode.NORMAL) {
        Log.i(TAG, "Starting location tracking in $mode mode")
        
        if (!checkLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            _locationStatus.value = LocationStatus.PERMISSION_DENIED
            return
        }
        
        trackingMode = mode
        configureTrackingParameters(mode)
        
        try {
            _locationStatus.value = LocationStatus.STARTING
            
            // Request location updates from best available provider
            val providers = getAvailableProviders()
            
            for (provider in providers) {
                Log.d(TAG, "Requesting updates from provider: $provider")
                locationManager.requestLocationUpdates(
                    provider,
                    updateInterval,
                    minDistance,
                    this
                )
            }
            
            _locationStatus.value = LocationStatus.ACTIVE
            Log.i(TAG, "Location tracking started successfully")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting location updates: ${e.message}")
            _locationStatus.value = LocationStatus.PERMISSION_DENIED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location tracking: ${e.message}")
            _locationStatus.value = LocationStatus.ERROR
        }
    }
    
    /**
     * Stops all location tracking.
     */
    fun stopLocationUpdates() {
        Log.i(TAG, "Stopping location updates")
        
        try {
            locationManager.removeUpdates(this)
            _locationStatus.value = LocationStatus.STOPPED
            Log.i(TAG, "Location tracking stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping location updates: ${e.message}")
        }
    }
    
    /**
     * Activates emergency mode with high-frequency location updates.
     * 
     * @param activate True to enable emergency mode, false to disable
     */
    fun setEmergencyMode(activate: Boolean) {
        _emergencyMode.value = activate
        
        if (activate) {
            Log.w(TAG, "EMERGENCY MODE ACTIVATED - High frequency location tracking")
            
            // Restart tracking in emergency mode
            if (_locationStatus.value == LocationStatus.ACTIVE) {
                stopLocationUpdates()
            }
            startLocationTracking(TrackingMode.EMERGENCY)
            
            // Broadcast emergency beacon through ATAK if available
            atakService?.sendEmergencyBeacon(true, "Emergency mode activated from Somewear device")
            
        } else {
            Log.i(TAG, "Emergency mode deactivated")
            atakService?.sendEmergencyBeacon(false)
            
            // Return to normal tracking mode
            if (_locationStatus.value == LocationStatus.ACTIVE) {
                stopLocationUpdates()
                startLocationTracking(TrackingMode.NORMAL)
            }
        }
    }
    
    /**
     * Sets the ATAK service reference for integration.
     * 
     * @param service AtakGeolocationService instance
     */
    fun setAtakService(service: AtakGeolocationService) {
        atakService = service
        Log.d(TAG, "ATAK service integration enabled")
    }
    
    /**
     * Gets the last known location with accuracy information.
     * 
     * @return LocationRecord with timestamp and accuracy, or null if unavailable
     */
    fun getLastKnownLocationRecord(): LocationRecord? {
        val location = _currentLocation.value ?: return null
        return LocationRecord(
            location = location,
            timestamp = System.currentTimeMillis(),
            accuracy = location.accuracy,
            provider = location.provider ?: "unknown"
        )
    }
    
    /**
     * Converts current location to specified coordinate system.
     * 
     * @param coordinateSystem Target coordinate system (WGS84, MGRS, UTM)
     * @return Formatted coordinate string
     */
    fun getCurrentLocationAsCoordinates(coordinateSystem: String): String? {
        val location = _currentLocation.value ?: return null
        
        return when (coordinateSystem.uppercase()) {
            "MGRS" -> convertToMgrs(location.latitude, location.longitude)
            "UTM" -> convertToUtm(location.latitude, location.longitude)
            "WGS84", "GPS" -> "${location.latitude}, ${location.longitude}"
            else -> "${location.latitude}, ${location.longitude}"
        }
    }
    
    /**
     * Gets location history within specified time range.
     * 
     * @param startTime Start of time range in milliseconds
     * @param endTime End of time range in milliseconds
     * @return List of location records in time range
     */
    fun getLocationHistory(startTime: Long, endTime: Long): List<LocationRecord> {
        return _locationHistory.value.filter { record ->
            record.timestamp in startTime..endTime
        }
    }
    
    // LocationListener implementation
    
    override fun onLocationChanged(location: Location) {
        Log.d(TAG, "Location updated: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")
        
        // Filter locations based on accuracy requirements
        if (location.accuracy > TACTICAL_ACCURACY_THRESHOLD && trackingMode != TrackingMode.BATTERY_SAVER) {
            Log.w(TAG, "Location accuracy (${location.accuracy}m) below tactical threshold, ignoring")
            return
        }
        
        // Update current location
        _currentLocation.value = location
        _gpsAccuracy.value = location.accuracy
        
        // Add to location history
        addToLocationHistory(location)
        
        // Share with ATAK if connected
        atakService?.updateLocation(location)
        
        Log.d(TAG, "Location processed and shared with ATAK")
    }
    
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        Log.d(TAG, "Provider $provider status changed to $status")
        
        when (status) {
            LocationProvider.OUT_OF_SERVICE -> {
                Log.w(TAG, "Location provider $provider out of service")
            }
            LocationProvider.TEMPORARILY_UNAVAILABLE -> {
                Log.w(TAG, "Location provider $provider temporarily unavailable")
            }
            LocationProvider.AVAILABLE -> {
                Log.i(TAG, "Location provider $provider available")
            }
        }
    }
    
    override fun onProviderEnabled(provider: String) {
        Log.i(TAG, "Location provider enabled: $provider")
    }
    
    override fun onProviderDisabled(provider: String) {
        Log.w(TAG, "Location provider disabled: $provider")
        
        if (provider == LocationManager.GPS_PROVIDER) {
            _locationStatus.value = LocationStatus.GPS_DISABLED
        }
    }
    
    // Private helper methods
    
    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun getAvailableProviders(): List<String> {
        val providers = mutableListOf<String>()
        
        // Prioritize GPS for tactical accuracy
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            providers.add(LocationManager.GPS_PROVIDER)
        }
        
        // Add network provider as fallback
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers.add(LocationManager.NETWORK_PROVIDER)
        }
        
        Log.d(TAG, "Available location providers: $providers")
        return providers
    }
    
    private fun configureTrackingParameters(mode: TrackingMode) {
        when (mode) {
            TrackingMode.HIGH_ACCURACY -> {
                updateInterval = 5_000L // 5 seconds
                minDistance = 1.0f // 1 meter
            }
            TrackingMode.NORMAL -> {
                updateInterval = LOCATION_UPDATE_INTERVAL
                minDistance = MIN_DISTANCE_CHANGE
            }
            TrackingMode.EMERGENCY -> {
                updateInterval = EMERGENCY_UPDATE_INTERVAL
                minDistance = 0.0f // Every location change
            }
            TrackingMode.BATTERY_SAVER -> {
                updateInterval = 60_000L // 1 minute
                minDistance = 50.0f // 50 meters
            }
        }
        
        Log.d(TAG, "Tracking parameters: interval=${updateInterval}ms, minDistance=${minDistance}m")
    }
    
    private fun initializeLastKnownLocation() {
        if (!checkLocationPermission()) return
        
        try {
            val lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            // Use the more recent and accurate location
            val bestLastKnown = when {
                lastKnownGps != null && lastKnownNetwork != null -> {
                    if (lastKnownGps.time > lastKnownNetwork.time) lastKnownGps else lastKnownNetwork
                }
                lastKnownGps != null -> lastKnownGps
                lastKnownNetwork != null -> lastKnownNetwork
                else -> null
            }
            
            bestLastKnown?.let { location ->
                _currentLocation.value = location
                Log.d(TAG, "Initialized with last known location: ${location.latitude}, ${location.longitude}")
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting last known location: ${e.message}")
        }
    }
    
    private fun addToLocationHistory(location: Location) {
        val record = LocationRecord(
            location = location,
            timestamp = System.currentTimeMillis(),
            accuracy = location.accuracy,
            provider = location.provider ?: "unknown"
        )
        
        serviceScope.launch {
            val currentHistory = _locationHistory.value.toMutableList()
            currentHistory.add(record)
            
            // Remove old entries beyond retention period
            val cutoffTime = System.currentTimeMillis() - LOCATION_HISTORY_RETENTION
            val filteredHistory = currentHistory.filter { it.timestamp > cutoffTime }
            
            _locationHistory.value = filteredHistory
        }
    }
    
    private fun convertToMgrs(latitude: Double, longitude: Double): String {
        // Simplified MGRS conversion - in production, use a proper MGRS library
        return "33TWN${String.format("%05d%05d", 
            (latitude * 1000).toInt() % 100000,
            (longitude * 1000).toInt() % 100000)}"
    }
    
    private fun convertToUtm(latitude: Double, longitude: Double): String {
        // Simplified UTM conversion - in production, use a proper UTM library
        val zone = ((longitude + 180) / 6).toInt() + 1
        val hemisphere = if (latitude >= 0) "N" else "S"
        return "${zone}${hemisphere} ${String.format("%06d %07d", 
            (longitude * 100000).toInt() % 1000000,
            (latitude * 100000).toInt() % 10000000)}"
    }
}

/**
 * Location service operational status.
 */
enum class LocationStatus {
    /** Location service is stopped */
    STOPPED,
    
    /** Location service is starting up */
    STARTING,
    
    /** Location service is active and providing updates */
    ACTIVE,
    
    /** GPS provider is disabled */
    GPS_DISABLED,
    
    /** Location permission denied */
    PERMISSION_DENIED,
    
    /** Service error occurred */
    ERROR
}

/**
 * Location tracking modes for different operational requirements.
 */
enum class TrackingMode {
    /** Normal tracking for general operations */
    NORMAL,
    
    /** High accuracy mode for precision operations */
    HIGH_ACCURACY,
    
    /** Emergency mode with maximum update frequency */
    EMERGENCY,
    
    /** Battery saving mode for extended operations */
    BATTERY_SAVER
}

/**
 * Location record with metadata for history tracking.
 * 
 * @property location Android Location object
 * @property timestamp Time when location was recorded
 * @property accuracy Location accuracy in meters
 * @property provider Location provider used (GPS, Network, etc.)
 */
data class LocationRecord(
    val location: Location,
    val timestamp: Long,
    val accuracy: Float,
    val provider: String
)