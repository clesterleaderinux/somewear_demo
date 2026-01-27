package com.example.somewear_demo

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test suite for the Somewear Demo application.
 * 
 * These tests execute on an Android device or emulator, allowing testing of
 * Android-specific functionality including:
 * - Application context and resources
 * - Activity lifecycle and UI interactions
 * - Bluetooth and hardware features
 * - Permissions and system services
 * - Integration between components
 * 
 * The tests run using the AndroidJUnit4 test runner which provides Android-specific
 * test utilities and access to the application under test.
 *
 * @see [Android Testing Documentation](http://d.android.com/tools/testing)
 * @author Somewear Demo Team
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    
    /**
     * Verifies that the application context is properly configured and accessible.
     * 
     * This test ensures:
     * - The test environment has access to the application context
     * - The package name matches the expected value
     * - The instrumentation framework is working correctly
     * 
     * This is a foundational test that validates the basic testing setup
     * for more complex instrumented tests.
     */
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.somewear_demo", appContext.packageName)
    }
}