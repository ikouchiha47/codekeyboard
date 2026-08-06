package com.codekeyboard

// Marker annotation — integration tests tagged with this load real assets
// and are excluded from the normal :testDebugUnitTest run.
// Run them with: ./gradlew integrationTest
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class IntegrationTest
