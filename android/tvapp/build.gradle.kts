plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Android TV / Google TV shell: hosts the shared web receiver (../../tv/dist) in a WebView and
// finds the phone sensor with NSD. Build the web app first: (cd tv && npm run build).
android {
    namespace = "dev.motionhub.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.motionhub.tv"
        minSdk = 26
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
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webassets"))
}

val webDist = rootDir.parentFile.resolve("tv/dist")
val copyWebReceiver by tasks.registering(Copy::class) {
    doFirst {
        if (!webDist.resolve("index.html").exists())
            throw GradleException("TV web receiver not built: run `npm install && npm run build` in ${webDist.parentFile}")
    }
    from(webDist)
    into(layout.buildDirectory.dir("generated/webassets/web"))
}
tasks.named("preBuild") { dependsOn(copyWebReceiver) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
