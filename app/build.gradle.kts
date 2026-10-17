plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.trtcnativecall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.trtcnativecall"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.tencent.liteav:LiteAVSDK_TRTC:latest.release")
}
