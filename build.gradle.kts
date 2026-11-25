import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // AGP 9 uses built-in Kotlin. Pin its KGP runtime to the project's
        // selected version rather than loading AGP's bundled default KGP.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

apply(plugin = "org.jetbrains.kotlin.jvm")

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

tasks.check {
    dependsOn(verifyPolicy)
}
