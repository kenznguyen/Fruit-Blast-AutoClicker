plugins {
    kotlin("jvm") version "2.4.21"
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
