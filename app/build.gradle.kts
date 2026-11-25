plugins {
    id("com.android.application") version "9.3.1"
    id("org.jetbrains.kotlin.android") version "2.4.21"
}

repositories {
    mavenCentral()
}

android {
    namespace = "com.fruitblast.autoclicker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fruitblast.autoclicker"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(project(":"))
}
