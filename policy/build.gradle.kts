plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("../src/main/kotlin")
    }
    test {
        kotlin.srcDir("../src/test/kotlin")
    }
}

val verifyPolicy by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs deterministic ad decision policy tests."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.fruitblast.automation.AdDecisionPolicyTestKt")
}
