import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
}

repositories {
    mavenCentral()
}

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(21)
}

val verifyPolicy by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs deterministic ad decision policy tests."
    classpath = configurations["testRuntimeClasspath"]
    mainClass.set("com.fruitblast.automation.AdDecisionPolicyTestKt")
}

tasks.named("check") {
    dependsOn(verifyPolicy)
}
