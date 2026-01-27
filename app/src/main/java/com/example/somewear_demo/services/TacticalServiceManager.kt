package com.example.somewear_demo.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Central service manager for coordinating ATAK integration and geospatial services.
 * 
 * This manager acts as a central coordinator for all tactical and geospatial services,
 * providing a unified interface for:
 * - Service lifecycle management
 * - Data flow coordination between services
 * - ATAK integration management
 * - Location services orchestration
 * - Tactical data synchronization
 * 
 * Service Integration:
 * - AtakGeolocationService: ATAK connectivity and PLI broadcasting
 * - SomewearLocationService: GPS tracking and location management
 * - GeospatialDataService: Tactical mapping and situational awareness
 * - BluetoothAudioManager: Voice data transmission integration
 * 
 * Data Flow:
 * 1. Location updates from SomewearLocationService
 * 2. Position shared with AtakGeolocationService for PLI broadcasting
 * 3. Tactical data queried from GeospatialDataService
 * 4. Combined situational awareness provided to UI and voice commands
 * 
 * @author Somewear Demo Team
 * @since 1.0
 */
class TacticalServiceManager(private val context: Context) {
    
    companion object {
        private const val TAG = "TacticalServiceManager"
        
        /** Service startup timeout in milliseconds */
        private const val SERVICE_STARTUP_TIMEOUT = 30_000L
    }
    
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /** Service instances */
    private var atakService: AtakGeolocationService? = null
    private var locationService: SomewearLocationService? = null
    private var geospatialService: GeospatialDataService? = null
    
    /** Service connection states */
    private val _servicesReady = MutableStateFlow(false)
    val servicesReady: StateFlow<Boolean> = _servicesReady.asStateFlow()
    
    /** Combined operational status */
    private val _operationalStatus = MutableStateFlow(TacticalOperationalStatus.INITIALIZING)
    val operationalStatus: StateFlow<TacticalOperationalStatus> = _operationalStatus.asStateFlow()
    
    /** Current tactical situation summary */
    private val _tacticalSituation = MutableStateFlow<TacticalSituation?>(null)
    val tacticalSituation: StateFlow<TacticalSituation?> = _tacticalSituation.asStateFlow()
    
    /** Emergency status aggregated from all services */
    private val _emergencyStatus = MutableStateFlow(false)
    val emergencyStatus: StateFlow<Boolean> = _emergencyStatus.asStateFlow()
    
    /** Service connections */
    private val atakConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            atakService = (service as AtakGeolocationService.AtakBinder).getService()
            Log.i(TAG, "ATAK Geolocation Service connected")
            checkAllServicesReady()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            atakService = null
            Log.w(TAG, "ATAK Geolocation Service disconnected")
            _servicesReady.value = false
        }
    }
    
    private val locationConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            locationService = (service as SomewearLocationService.LocationBinder).getService()
            Log.i(TAG, "Location Service connected")
            
            // Set up ATAK integration
            atakService?.let { atak ->
                locationService?.setAtakService(atak)
            }
            
            // Start monitoring location updates
            startLocationMonitoring()
            checkAllServicesReady()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            locationService = null
            Log.w(TAG, "Location Service disconnected")
            _servicesReady.value = false
        }
    }
    
    private val geospatialConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            geospatialService = (service as GeospatialDataService.GeospatialBinder).getService()
            Log.i(TAG, "Geospatial Data Service connected")
            checkAllServicesReady()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            geospatialService = null
            Log.w(TAG, "Geospatial Data Service disconnected")
            _servicesReady.value = false
        }
    }
    
    /** Data monitoring jobs */
    private var locationMonitoringJob: Job? = null
    private var situationUpdateJob: Job? = null
    
    /**
     * Initializes and starts all tactical services.
     */
    fun initializeServices() {
        Log.i(TAG, "Initializing tactical services")
        _operationalStatus.value = TacticalOperationalStatus.STARTING
        
        managerScope.launch {
            try {
                // Start all services
                startAtakService()
                startLocationService()
                startGeospatialService()
                
                // Wait for all services to be ready with timeout
                withTimeout(SERVICE_STARTUP_TIMEOUT) {
                    _servicesReady.first { it }
                }
                
                // Initialize cross-service integration
                initializeServiceIntegration()
                
                _operationalStatus.value = TacticalOperationalStatus.OPERATIONAL
                Log.i(TAG, "All tactical services operational")
                
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Service startup timeout")
                _operationalStatus.value = TacticalOperationalStatus.ERROR
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize services: ${e.message}")
                _operationalStatus.value = TacticalOperationalStatus.ERROR
            }
        }
    }
    
    /**
     * Connects to ATAK and starts tactical data sharing.
     */
    fun connectToAtak() {
        atakService?.connectToAtak()
        Log.i(TAG, "Initiating ATAK connection")
    }
    
    /**
     * Starts location tracking with specified mode.
     * 
     * @param mode Tracking mode for different operational requirements
     */
    fun startLocationTracking(mode: TrackingMode = TrackingMode.NORMAL) {
        locationService?.startLocationTracking(mode)
        Log.i(TAG, "Location tracking started in $mode mode")
    }
    
    /**
     * Activates emergency mode across all services.
     * 
     * @param activate True to enable emergency mode
     * @param message Optional emergency message
     */
    fun setEmergencyMode(activate: Boolean, message: String? = null) {
        _emergencyStatus.value = activate
        
        locationService?.setEmergencyMode(activate)
        atakService?.sendEmergencyBeacon(activate, message)
        
        Log.w(TAG, "Emergency mode ${if (activate) "ACTIVATED" else "DEACTIVATED"}")
    }
    
    /**
     * Gets current location in specified coordinate format.
     * 
     * @param format Coordinate format (WGS84, MGRS, UTM)
     * @return Formatted location string or null if unavailable
     */
    fun getCurrentLocationFormatted(format: String): String? {
        return locationService?.getCurrentLocationAsCoordinates(format)
    }
    
    /**
     * Queries tactical features near current position.
     * 
     * @param rangeMeters Search radius in meters
     * @param featureTypes Optional filter for feature types
     * @return List of nearby tactical features
     */
    fun queryNearbyTacticalFeatures(
        rangeMeters: Double = 5000.0,
        featureTypes: List<String>? = null
    ): List<TacticalFeature>? {
        val currentLocation = locationService?.currentLocation?.value
        val geoPoint = currentLocation?.let { 
            GeoPoint(it.latitude, it.longitude) 
        }
        
        return geoPoint?.let { point ->
            geospatialService?.queryTacticalFeatures(point, rangeMeters, featureTypes)
        }
    }
    
    /**
     * Creates a mission route from voice-described waypoints.
     * 
     * @param routeId Unique route identifier
     * @param waypoints List of waypoints
     * @param routeType Type of mission route
     */
    fun createMissionRoute(
        routeId: String,
        waypoints: List<Waypoint>,
        routeType: RouteType = RouteType.PATROL
    ) {
        geospatialService?.createMissionRoute(routeId, waypoints, routeType)
        Log.i(TAG, "Mission route '$routeId' created with ${waypoints.size} waypoints")
    }
    
    /**
     * Gets bearing and distance to nearest tactical feature of specified type.
     * 
     * @param featureType Type of feature to find
     * @return BearingDistance to nearest feature, or null if none found
     */
    fun getBearingToNearestFeature(featureType: String): BearingDistance? {
        val currentLocation = locationService?.currentLocation?.value
        val currentPoint = currentLocation?.let { 
            GeoPoint(it.latitude, it.longitude) 
        }
        
        return currentPoint?.let { point ->
            geospatialService?.findNearestFeature(point, featureType)?.let { feature ->
                val targetPoint = GeoPoint(feature.latitude, feature.longitude)
                geospatialService?.calculateBearingDistance(point, targetPoint)
            }
        }
    }
    
    /**
     * Exports current tactical situation as ATAK-compatible data.
     * 
     * @return JSON string for ATAK import
     */
    fun exportTacticalSituationForAtak(): String? {
        return geospatialService?.exportTacticalDataForAtak()
    }
    
    /**
     * Shuts down all tactical services.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down tactical services")
        
        locationMonitoringJob?.cancel()
        situationUpdateJob?.cancel()
        
        try {
            context.unbindService(atakConnection)
            context.unbindService(locationConnection) 
            context.unbindService(geospatialConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Service already unbound: ${e.message}")
        }
        
        _operationalStatus.value = TacticalOperationalStatus.STOPPED
        managerScope.cancel()
    }
    
    // Private helper methods
    
    private fun startAtakService() {
        val intent = Intent(context, AtakGeolocationService::class.java)
        context.startService(intent)
        context.bindService(intent, atakConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun startLocationService() {
        val intent = Intent(context, SomewearLocationService::class.java)
        context.startService(intent)
        context.bindService(intent, locationConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun startGeospatialService() {
        val intent = Intent(context, GeospatialDataService::class.java)
        context.startService(intent)
        context.bindService(intent, geospatialConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun checkAllServicesReady() {
        val allReady = atakService != null && 
                      locationService != null && 
                      geospatialService != null
        
        if (allReady && !_servicesReady.value) {
            _servicesReady.value = true
            Log.i(TAG, "All tactical services ready")
        }
    }
    
    private fun initializeServiceIntegration() {
        // Set up location service to share data with ATAK
        locationService?.let { location ->
            atakService?.let { atak ->
                location.setAtakService(atak)
            }
        }
        
        // Set default area of operations based on current location
        locationService?.currentLocation?.value?.let { location ->
            val center = GeoPoint(location.latitude, location.longitude)
            geospatialService?.setAreaOfOperations(center, 10000.0) // 10km radius
        }
        
        // Start continuous situation monitoring
        startSituationMonitoring()
    }
    
    private fun startLocationMonitoring() {
        locationMonitoringJob = managerScope.launch {
            locationService?.currentLocation?.collect { location ->
                location?.let { updateTacticalSituation(it) }
            }
        }
    }
    
    private fun startSituationMonitoring() {
        situationUpdateJob = managerScope.launch {
            while (isActive) {
                updateTacticalSituationSummary()
                delay(30_000) // Update every 30 seconds
            }
        }
    }
    
    private fun updateTacticalSituation(location: Location) {
        managerScope.launch {
            val geoPoint = GeoPoint(location.latitude, location.longitude)
            
            // Query nearby tactical features
            val nearbyFeatures = geospatialService?.queryTacticalFeatures(geoPoint, 5000.0) ?: emptyList()
            
            // Generate threat assessment
            val threatAssessment = geospatialService?.generateThreatAssessment(geoPoint)
            
            // Update tactical situation
            _tacticalSituation.value = TacticalSituation(
                currentPosition = geoPoint,
                timestamp = System.currentTimeMillis(),
                nearbyFeatures = nearbyFeatures,
                threatAssessment = threatAssessment,
                atakConnected = atakService?.connectionStatus?.value == AtakConnectionStatus.CONNECTED,
                emergencyActive = _emergencyStatus.value
            )
        }
    }
    
    private fun updateTacticalSituationSummary() {
        // Periodic comprehensive situation update
        locationService?.currentLocation?.value?.let { location ->
            updateTacticalSituation(location)
        }
    }
}

/**
 * Overall tactical operational status.
 */
enum class TacticalOperationalStatus {
    /** Services are initializing */
    INITIALIZING,
    
    /** Services are starting up */
    STARTING,
    
    /** All services operational */
    OPERATIONAL,
    
    /** Degraded operations (some services unavailable) */
    DEGRADED,
    
    /** Service error occurred */
    ERROR,
    
    /** Services stopped */
    STOPPED
}

/**
 * Comprehensive tactical situation summary.
 * 
 * @property currentPosition Current geographic position
 * @property timestamp Situation timestamp
 * @property nearbyFeatures Tactical features in immediate area
 * @property threatAssessment Current threat assessment
 * @property atakConnected ATAK connectivity status
 * @property emergencyActive Emergency mode status
 */
data class TacticalSituation(
    val currentPosition: GeoPoint,
    val timestamp: Long,
    val nearbyFeatures: List<TacticalFeature>,
    val threatAssessment: ThreatAssessment?,
    val atakConnected: Boolean,
    val emergencyActive: Boolean
)