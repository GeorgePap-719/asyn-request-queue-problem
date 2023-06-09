buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:0.20.1")
    }
}

apply(plugin = "kotlinx-atomicfu")

plugins {
    kotlin("jvm") version "1.8.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0-Beta")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.0-Beta")
    testImplementation("org.jetbrains.kotlinx:lincheck:2.16")
    testImplementation("junit:junit:4.13.1")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(// required lincheck
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/sun.security.util=ALL-UNNAMED"
    )
}

kotlin {
    jvmToolchain(11)
}