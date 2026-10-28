buildscript {
    repositories { google(); mavenCentral() }
    // Nearby 19.5.1 needs a newer metadata reader than AGP's bundled Kotlin 2.2.
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21") }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
