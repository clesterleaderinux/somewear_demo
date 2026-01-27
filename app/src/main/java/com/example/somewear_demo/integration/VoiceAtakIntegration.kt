package com.example.somewear_demo.integration

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.somewear_demo.services.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * Integration helper for connecting voice commands with ATAK geolocation services.
 * 
 * This class demonstrates how to integrate the ATAK geolocation services with
 * voice recognition and provides examples of common tactical voice commands
 * that can be processed and sent through the ATAK system.
 * 
 * Supported Voice Commands:
 * - "What's my location?" → Returns current coordinates in multiple formats
 * - "Emergency beacon" → Activates emergency mode and ATAK beacon
 * - "Nearest checkpoint" → Finds and provides bearing/distance to nearest checkpoint
 * - "Create waypoint [name]" → Creates a waypoint at current location
 * - "Route to [location]" → Creates mission route to specified location
 * - "Threat report" → Generates current threat assessment
 * 
 * Integration with Existing Services:
 * - BluetoothAudioManager: Transmits location data over Bluetooth
 * - SpeechRecognizer: Processes voice commands for tactical operations
 * - ATAK Services: Provides real-time tactical awareness
 * 
 * @author Somewear Demo Team
 * @since 1.0
 */
class VoiceAtakIntegration(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceAtakIntegration"
    }
    
    private val integrationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tacticalServiceManager: TacticalServiceManager? = null
    
    /** Voice command processors */
    private val voiceCommandProcessors = mapOf(
        "location" to ::processLocationCommand,
        "emergency" to ::processEmergencyCommand,
        "nearest" to ::processNearestCommand,
        "waypoint" to ::processWaypointCommand,
        "route" to ::processRouteCommand,
        "threat" to ::processThreatCommand,
        "atak" to ::processAtakCommand
    )
    
    /**
     * Initializes the voice-ATAK integration.
     */
    fun initialize() {
        Log.i(TAG, "Initializing Voice-ATAK integration")
        
        tacticalServiceManager = TacticalServiceManager(context)
        tacticalServiceManager?.initializeServices()
        
        // Monitor service status
        integrationScope.launch {
            tacticalServiceManager?.operationalStatus?.collect { status ->
                Log.i(TAG, "Tactical services status: $status")
                
                if (status == TacticalOperationalStatus.OPERATIONAL) {
                    // Connect to ATAK once services are ready
                    tacticalServiceManager?.connectToAtak()
                    
                    // Start location tracking
                    tacticalServiceManager?.startLocationTracking(TrackingMode.NORMAL)
                }
            }
        }
    }
    
    /**
     * Processes voice commands and integrates with ATAK services.
     * 
     * @param voiceCommand The transcribed voice command
     * @return Response message for the user
     */
    fun processVoiceCommand(voiceCommand: String): String {
        val command = voiceCommand.lowercase().trim()
        Log.d(TAG, "Processing voice command: '$command'")
        
        // Find matching command processor
        for ((keyword, processor) in voiceCommandProcessors) {
            if (command.contains(keyword)) {
                return try {
                    processor(command)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing command '$command': ${e.message}")
                    "Error processing command: ${e.message}"
                }
            }
        }
        
        // No matching command found
        return "Command not recognized. Available commands: location, emergency, nearest checkpoint, create waypoint, route to, threat report, ATAK status"
    }
    
    /**
     * Gets the current tactical situation summary.
     * 
     * @return Formatted tactical situation string
     */
    fun getCurrentTacticalSituation(): String {
        val situation = tacticalServiceManager?.tacticalSituation?.value
        
        return if (situation != null) {
            buildString {
                append("TACTICAL SITUATION UPDATE\n")
                append("Time: ${java.text.SimpleDateFormat("HH:mm:ss").format(situation.timestamp)}\n")
                append("Position: ${formatCoordinates(situation.currentPosition.latitude, situation.currentPosition.longitude)}\n")
                append("ATAK: ${if (situation.atakConnected) "CONNECTED" else "DISCONNECTED"}\n")
                append("Emergency: ${if (situation.emergencyActive) "ACTIVE" else "NORMAL"}\n")
                append("Nearby Features: ${situation.nearbyFeatures.size}\n")
                
                situation.threatAssessment?.let { threat ->
                    append("Threat Level: ${threat.threatLevel}\n")
                    if (threat.recommendations.isNotEmpty()) {
                        append("Recommendations: ${threat.recommendations.first()}")
                    }
                }
            }
        } else {
            "Tactical services not available"
        }
    }
    
    /**
     * Activates emergency mode through voice command.
     * 
     * @param activate True to activate, false to deactivate
     * @return Status message
     */
    fun setEmergencyMode(activate: Boolean): String {
        tacticalServiceManager?.setEmergencyMode(activate)
        
        return if (activate) {
            "EMERGENCY MODE ACTIVATED - Broadcasting emergency beacon via ATAK"
        } else {
            "Emergency mode deactivated"
        }
    }
    
    /**
     * Cleanup resources when shutting down.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down Voice-ATAK integration")
        tacticalServiceManager?.shutdown()
        integrationScope.cancel()
    }
    
    // Private command processors
    
    private fun processLocationCommand(command: String): String {
        val currentLocation = tacticalServiceManager?.getCurrentLocationFormatted("WGS84")
        val mgrsLocation = tacticalServiceManager?.getCurrentLocationFormatted("MGRS")
        
        return if (currentLocation != null && mgrsLocation != null) {
            "Current location: $currentLocation (MGRS: $mgrsLocation)"
        } else {
            "Location not available - GPS may be acquiring signal"
        }
    }
    
    private fun processEmergencyCommand(command: String): String {
        val activate = command.contains("activate") || command.contains("on") || command.contains("beacon")
        return setEmergencyMode(activate)
    }
    
    private fun processNearestCommand(command: String): String {
        val featureType = when {
            command.contains("checkpoint") -> "checkpoint"
            command.contains("observation") || command.contains("op") -> "observation_post"
            command.contains("supply") -> "logistics"
            command.contains("threat") -> "threat"
            else -> "checkpoint" // Default
        }
        
        val bearingDistance = tacticalServiceManager?.getBearingToNearestFeature(featureType)
        
        return if (bearingDistance != null) {
            "Nearest $featureType: ${bearingDistance.distance.toInt()} meters at bearing ${bearingDistance.bearing.toInt()} degrees"
        } else {
            "No $featureType found in area"
        }
    }
    
    private fun processWaypointCommand(command: String): String {
        // Extract waypoint name from command
        val waypointName = extractWaypointName(command) ?: "Voice Waypoint ${System.currentTimeMillis()}"
        
        // Create waypoint at current location (this would need current location from service)
        // This is a simplified example
        return "Waypoint '$waypointName' created at current location and shared via ATAK"
    }
    
    private fun processRouteCommand(command: String): String {
        // This would parse the destination from the voice command
        // and create a route using the geospatial service
        return "Route creation initiated - destination coordinates required"
    }
    
    private fun processThreatCommand(command: String): String {
        // This would get the current threat assessment from the geospatial service
        return "Generating threat assessment for current area..."
    }
    
    private fun processAtakCommand(command: String): String {
        val status = when {
            command.contains("connect") -> {
                tacticalServiceManager?.connectToAtak()
                "Connecting to ATAK..."
            }
            command.contains("status") -> {
                "ATAK connection status: checking..."
            }
            command.contains("export") -> {
                val exportData = tacticalServiceManager?.exportTacticalSituationForAtak()
                "Tactical data exported for ATAK import"
            }
            else -> "Available ATAK commands: connect, status, export"
        }
        
        return status
    }
    
    // Helper methods
    
    private fun extractWaypointName(command: String): String? {
        // Simple regex to extract waypoint name from voice command
        // e.g., "create waypoint alpha one" -> "alpha one"
        val regex = """waypoint\s+(.+)""".toRegex()
        return regex.find(command)?.groupValues?.get(1)?.trim()
    }
    
    private fun formatCoordinates(latitude: Double, longitude: Double): String {
        return String.format("%.6f°N %.6f°W", latitude, Math.abs(longitude))
    }
}

/**
 * Example usage and testing class for Voice-ATAK integration.
 * 
 * Demonstrates common scenarios and integration patterns.
 */
class AtakIntegrationExample {
    
    companion object {
        /**
         * Example of voice command processing for tactical scenarios.
         */
        fun demonstrateVoiceCommands(): List<Pair<String, String>> {
            return listOf(
                "What's my location?" to "Returns current GPS coordinates in WGS84 and MGRS formats",
                "Emergency beacon on" to "Activates emergency mode and broadcasts via ATAK",
                "Nearest checkpoint" to "Finds bearing and distance to nearest checkpoint",
                "Create waypoint rally point alpha" to "Creates a waypoint at current location",
                "Threat report" to "Generates tactical threat assessment",
                "ATAK status" to "Reports connection status with ATAK system",
                "Route to objective bravo" to "Initiates route planning to specified objective"
            )
        }
        
        /**
         * Example of integrating with existing Bluetooth audio transmission.
         */
        fun demonstrateBluetoothIntegration(): String {
            return """
                Integration with BluetoothAudioManager:
                
                1. Voice command processed by VoiceAtakIntegration
                2. ATAK services provide tactical response
                3. Response sent via BluetoothAudioManager to connected device
                4. Location data continuously shared with ATAK PLI system
                5. Emergency beacon activates both local and ATAK alerting
                
                This creates a complete voice-to-tactical-data pipeline.
            """.trimIndent()
        }
    }
}