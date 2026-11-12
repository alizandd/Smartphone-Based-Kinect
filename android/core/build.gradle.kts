import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM tracking core: geometry, calibration, filters, gesture detectors, MSP codec.
// Shared by the Android app and the desktop replay CLI, and unit-testable without a phone.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

application {
    mainClass.set("dev.motionhub.core.replay.ReplayCliKt")
    applicationName = "replay"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("golden.dir", rootDir.parentFile.resolve("protocol/golden").absolutePath)
}
