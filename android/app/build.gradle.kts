import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.motionhub.sensor"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.motionhub.sensor"
        minSdk = 30          // getThermalHeadroom, low-latency Wi-Fi lock
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-poc"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // PoC only
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
    packaging {
        resources { excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module") }
    }
}

// MediaPipe models (Apache 2.0) are downloaded at build time, not committed.
val mediapipeModels = listOf("lite", "full", "heavy").associate { v ->
    "pose_landmarker_$v.task" to "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_$v/float16/latest/pose_landmarker_$v.task"
} + mapOf(
    "hand_landmarker.task" to "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task",
    "face_landmarker.task" to "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task",
)
val downloadPoseModels by tasks.registering {
    val assets = layout.projectDirectory.dir("src/main/assets")
    outputs.files(mediapipeModels.keys.map { assets.file(it) })
    doLast {
        mediapipeModels.forEach { (name, url) ->
            val target = assets.file(name).asFile
            if (!target.exists()) {
                target.parentFile.mkdirs()
                logger.lifecycle("Downloading $url")
                // Download to a temp file first so an interrupted build never leaves a truncated model.
                val part = File(target.path + ".part")
                URI(url).toURL().openStream().use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                check(part.renameTo(target)) { "could not move $part to $target" }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadPoseModels) }

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("org.slf4j:slf4j-nop:2.0.13")
}
