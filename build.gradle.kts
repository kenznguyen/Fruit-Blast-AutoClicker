buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // AGP 9 built-in Kotlin uses this classpath; pin it to the project's
        // supported Kotlin Gradle Plugin version to avoid loading AGP's
        // bundled default KGP alongside a different root-project KGP.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

val verifyPolicy by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs deterministic ad decision policy tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.fruitblast.automation.AdDecisionPolicyTestKt")
}

tasks.check {
    dependsOn(verifyPolicy)
}
