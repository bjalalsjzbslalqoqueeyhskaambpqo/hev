import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appConfig = Properties().apply {
    val configFile = file("app-config.properties")
    if (configFile.exists()) {
        configFile.inputStream().use { load(it) }
    }
}

fun prop(name: String, default: String): String =
    (appConfig.getProperty(name) ?: System.getenv(name) ?: default).trim().ifEmpty { default }

android {
    namespace = "com.blacktunnel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blacktunnel"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.0"

        buildConfigField("String", "SERVER_URL", "\"${prop("SERVER_URL", "https://example.com") }\"")
        buildConfigField("String", "CLIENT_DEFAULT_IDENTIFIER", "\"${prop("CLIENT_DEFAULT_IDENTIFIER", "") }\"")
        buildConfigField("String", "RESELLER_IDENTIFIER", "\"${prop("RESELLER_IDENTIFIER", "") }\"")
        buildConfigField("String", "RESELLER_ADMIN_USER", "\"${prop("RESELLER_ADMIN_USER", "") }\"")
        buildConfigField("String", "RESELLER_ADMIN_PASS", "\"${prop("RESELLER_ADMIN_PASS", "") }\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    flavorDimensions += "appType"
    productFlavors {
        create("client") {
            dimension = "appType"
            applicationIdSuffix = ".client"
            versionNameSuffix = "-client"
            buildConfigField("String", "APP_MODE", "\"client\"")
        }
        create("reseller") {
            dimension = "appType"
            applicationIdSuffix = ".reseller"
            versionNameSuffix = "-reseller"
            buildConfigField("String", "APP_MODE", "\"reseller\"")
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}
