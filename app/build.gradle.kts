import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.apollo)
}

android {
    namespace = "com.gocavgo.ikuriye"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    val secretsFile = rootProject.file("secrets.properties")
    val secretsProperties = if (secretsFile.exists()) {
        Properties().apply {
            secretsFile.inputStream().use { load(it) }
        }
    } else {
        Properties()
    }

    defaultConfig {
        applicationId = "com.gocavgo.ikuriye"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${secretsProperties.getProperty("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${secretsProperties.getProperty("SUPABASE_KEY", "")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    // supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.supabase.postgrest.kt)
    implementation(libs.supabase.auth.kt)
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // ── Compose BOM — keeps all Compose versions consistent ──────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // apollo graphql
    implementation(libs.apollo.runtime)

    // ── Material Icons Extended — for delivery/navigation icons ──────────
    implementation(libs.androidx.compose.material.icons.extended)

    // ── Core & Lifecycle ──────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ── ViewModel + Compose integration ──────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // ── Core Library Desugaring — enables java.time.* on API < 26 ──────
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // ── Google Play Services Location (FusedLocationProviderClient) ───────
    // Works on low-end devices; handles GPS, network, and passive providers
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // ── Fragment (explicit dep for ActivityResultContracts compat) ──────
    implementation(libs.androidx.fragment.ktx)

    // ── Media3 ExoPlayer for video playback ───────────────────────────────
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")


    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

apollo {
    service("service") {
        packageName.set("com.gocavgo.ikuriye")
        introspection {
            endpointUrl.set("https://api.med.rw/deliveries/graphql")
            schemaFile.set(file("src/main/graphql/schema.graphqls"))
        }
    }
}