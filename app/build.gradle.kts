import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sellerCodeFile = rootProject.file("SELLER_CODE.txt")
val sellerCodeRaw = if (sellerCodeFile.exists()) sellerCodeFile.readText().trim() else "DEMO-CODE"
val sellerCodeEscaped = sellerCodeRaw.replace("\\", "\\\\").replace("\"", "\\\"")

val panelConfigFile = rootProject.file("PANEL_CONFIG.txt")
val panelConfigRaw = if (panelConfigFile.exists()) panelConfigFile.readText().trim() else ""
val (baseUrlRaw, tokenRaw) = if (panelConfigRaw.isBlank()) {
    "" to ""
} else {
    val parts = panelConfigRaw.lines()
        .firstOrNull { it.trim().isNotEmpty() }
        ?.trim()
        ?.split(Regex("\\s+"), limit = 2)
        ?: emptyList()

    if (parts.size < 2) {
        throw GradleException("PANEL_CONFIG.txt debe tener: <BASE_URL> <TOKEN>")
    }
    parts[0] to parts[1]
}

val baseUrlEscaped = baseUrlRaw.replace("\\", "\\\\").replace("\"", "\\\"")
val tokenEscaped = tokenRaw.replace("\\", "\\\\").replace("\"", "\\\"")
val hasInjectedConfig = baseUrlRaw.isNotBlank() && tokenRaw.isNotBlank()

val appIdentityFile = rootProject.file("APP_IDENTITY.txt")
val appIdentityDefaults = mapOf(
    "APPLICATION_ID" to "com.blacktunnel.panel",
    "APP_NAME" to "Admin SSH"
)
val appIdentityRaw = if (appIdentityFile.exists()) appIdentityFile.readText() else ""
val appIdentityOverrides = appIdentityRaw
    .lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
        val separatorIndex = line.indexOf('=')
        if (separatorIndex <= 0) {
            return@mapNotNull null
        }
        val key = line.substring(0, separatorIndex).trim()
        val value = line.substring(separatorIndex + 1).trim()
        key to value
    }
    .toMap()

val applicationIdRaw = appIdentityOverrides["APPLICATION_ID"]?.ifBlank { null }
    ?: appIdentityDefaults.getValue("APPLICATION_ID")
val appNameRaw = appIdentityOverrides["APP_NAME"]?.ifBlank { null }
    ?: appIdentityDefaults.getValue("APP_NAME")
val appNameEscaped = appNameRaw.replace("\\", "\\\\").replace("\"", "\\\"")

val validApplicationIdRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
if (!validApplicationIdRegex.matches(applicationIdRaw)) {
    throw GradleException("APP_IDENTITY.txt APPLICATION_ID inválido: $applicationIdRaw")
}

val signingStoreFile = System.getenv("SIGNING_STORE_FILE")
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning = !signingStoreFile.isNullOrBlank() && !signingStorePassword.isNullOrBlank() && !signingKeyAlias.isNullOrBlank() && !signingKeyPassword.isNullOrBlank()

android {
    namespace = "com.blacktunnel.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = applicationIdRaw
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.0"

        resValue("string", "app_name", "\"$appNameEscaped\"")
        buildConfigField("String", "SELLER_CODE", "\"$sellerCodeEscaped\"")
        buildConfigField("String", "INJECTED_BASE_URL", "\"$baseUrlEscaped\"")
        buildConfigField("String", "INJECTED_TOKEN", "\"$tokenEscaped\"")
        buildConfigField("boolean", "HAS_INJECTED_CONFIG", hasInjectedConfig.toString())

    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
