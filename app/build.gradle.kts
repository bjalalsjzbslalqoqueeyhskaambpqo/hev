plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sellerCodeFile = rootProject.file("SELLER_CODE.txt")
val sellerCodeRaw = if (sellerCodeFile.exists()) sellerCodeFile.readText().trim() else "DEMO-CODE"
val sellerCodeEscaped = sellerCodeRaw.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.blacktunnel.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blacktunnel.panel"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        buildConfigField("String", "SELLER_CODE", "\"$sellerCodeEscaped\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
