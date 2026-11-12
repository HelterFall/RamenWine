plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .orNull
    ?.toIntOrNull()
    ?.takeIf { it > 0 }

val signingStorePath = providers.environmentVariable("RAMENWINE_SIGNING_KEYSTORE").orNull
val signingStorePassword = providers.environmentVariable("RAMENWINE_SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("RAMENWINE_SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("RAMENWINE_SIGNING_KEY_PASSWORD").orNull
val hasPersistentSigningKey = listOf(
    signingStorePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

val requirePersistentSigningKey =
    providers.environmentVariable("RAMENWINE_REQUIRE_PERSISTENT_SIGNING").orNull == "true"

if (requirePersistentSigningKey && !hasPersistentSigningKey) {
    throw org.gradle.api.GradleException(
        "CI APK signing is not configured. Add the RAMENWINE_SIGNING_BUNDLE_BASE64 " +
            "GitHub Actions secret before building an installable APK."
    )
}

android {
    namespace = "dev.ramenwine"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    signingConfigs {
        if (hasPersistentSigningKey) {
            create("persistentDebug") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    defaultConfig {
        applicationId = "dev.ramenwine"
        minSdk = 28
        // Wine needs SELinux execmod for its private PE-module mappings.
        // Android grants this legacy permission to app-private files only
        // for target API <= 28 (untrusted_app_27); newer app domains deny it.
        // Keep compileSdk current while preserving this runtime requirement.
        targetSdk = 28
        targetSdk = 36
        // GitHub run numbers make every CI APK an Android-upgrade (never downgrade).
        versionCode = ciRunNumber ?: 1
        versionName = ciRunNumber?.let { "0.1.0-ci.$it" } ?: "0.1.0"

        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasPersistentSigningKey) {
                signingConfig = signingConfigs.getByName("persistentDebug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += "zip"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
