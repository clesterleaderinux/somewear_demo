package com.example.somewear_demo

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test suite for the Somewear Demo application.
 * 
 * These tests execute on the development machine (host) rather than on an Android device,
 * making them fast and suitable for testing business logic without Android dependencies.
 * 
 * For testing Android-specific components like Activities, Services, or UI elements,
 * see the instrumented tests in the androidTest directory.
 *
 * @see [Android Testing Documentation](http://d.android.com/tools/testing)
 * @author Somewear Demo Team
 */
class ExampleUnitTest {
    
    /**
     * Basic arithmetic test to verify the testing framework is working correctly.
     * 
     * This simple test ensures that:
     * - JUnit is properly configured
     * - Assert functions are working
     * - The testing environment is set up correctly
     * 
     * @see assertEquals for assertion documentation
     */
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}