import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Validate the merged result, so dependency manifests or API caps cannot silently
// remove Nearby's normal Wi-Fi permissions from modern devices again.
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val verify = tasks.register("verifyDebugNearbyPermissions") {
        inputs.file(mergedManifest)
        doLast {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val document = factory.newDocumentBuilder().parse(mergedManifest.get().asFile)
            val nodes = document.getElementsByTagName("uses-permission")
            val namespace = "http://schemas.android.com/apk/res/android"
            for (permission in listOf("ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE")) {
                val matches = (0 until nodes.length).map { nodes.item(it) as Element }
                    .filter { it.getAttributeNS(namespace, "name") == "android.permission.$permission" }
                check(matches.size == 1 && matches.single().getAttributeNS(namespace, "maxSdkVersion").isEmpty()) {
                    "Nearby requires uncapped $permission in the merged manifest (including API 35/37)."
                }
            }
            println("Nearby merged-manifest Wi-Fi permissions verified for modern Android APIs.")
        }
    }
    tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn(verify) }
}

android {
    namespace = "dev.touchbridge"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.touchbridge"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.3.0"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    constraints {
        implementation("androidx.fragment:fragment:1.9.1") {
            because("Play services' legacy Fragment dependency must support Activity Result permissions")
        }
    }
    implementation("com.google.android.gms:play-services-nearby:19.5.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
