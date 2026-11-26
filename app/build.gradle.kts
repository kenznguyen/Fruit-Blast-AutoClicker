plugins {
    id("com.android.application")
}

repositories {
    google()
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":policy"))

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
