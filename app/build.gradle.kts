import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.apollo)
}

// ── Backend configuration (see secrets.properties.example) ───────────────────
// Loaded at the top level so both the android {} block (BuildConfig fields) and
// the apollo {} block (introspection endpoint) can read the same values.
val secretsFile = rootProject.file("secrets.properties")
val secretsProperties = if (secretsFile.exists()) {
    Properties().apply {
        secretsFile.inputStream().use { load(it) }
    }
} else {
    logger.warn("secrets.properties not found at project root — using fallback defaults. " +
        "Copy secrets.properties.example to secrets.properties and fill in your values.")
    Properties()
}

/**
 * Resolves a config value from secrets.properties first, then from an
 * environment variable of the same name (how CI passes GitHub secrets), then
 * the given default. [warn] controls whether a missing key logs a warning.
 */
fun secret(key: String, default: String, warn: Boolean = true): String {
    val fromFile = secretsProperties.getProperty(key)?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv(key)?.takeIf { it.isNotBlank() }
    val value = fromFile ?: fromEnv
    if (value == null && default.isNotEmpty() && warn) {
        logger.warn("secrets.properties / env is missing '$key' — falling back to '$default'.")
    }
    return value ?: default
}

// ── Optional release signing ────────────────────────────────────────────────
// Only configured when all keystore secrets are present (e.g. CI injects them
// from GitHub secrets as env vars). Absent locally → release APK is unsigned,
// matching the previous behaviour.
// Named with sig* prefixes to avoid colliding with the SigningConfig properties
// (storePassword / keyAlias / keyPassword), which made self-assignments ambiguous.
val sigKeystoreBase64 = secret("KEYSTORE_BASE64", "", warn = false)
val sigKeystorePassword = secret("KEYSTORE_PASSWORD", "", warn = false)
val sigKeyAlias = secret("KEY_ALIAS", "", warn = false)
val sigKeyPassword = secret("KEY_PASSWORD", "", warn = false)
val releaseSigningConfigured =
    listOf(sigKeystoreBase64, sigKeystorePassword, sigKeyAlias, sigKeyPassword).all { it.isNotBlank() }

android {
    namespace = "com.gocavgo.ikuriye"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.gocavgo.ikuriye"
        minSdk = 24
        targetSdk = 36
        // Overridable in CI (e.g. VERSION_NAME from the git tag, VERSION_CODE
        // from the commit count). Silent fallbacks keep local builds unchanged.
        versionCode = secret("VERSION_CODE", "1", warn = false).toIntOrNull() ?: 1
        versionName = secret("VERSION_NAME", "1.0", warn = false)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Backend endpoints & storage buckets — overridable via secrets.properties
        // Supabase is used for FILE UPLOADS ONLY — auth is handled by Nexxauth.
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${secret("SUPABASE_KEY", "")}\"")
        buildConfigField("String", "GRAPHQL_URL", "\"${secret("GRAPHQL_URL", "https://api.med.rw/deliveries/graphql")}\"")
        buildConfigField("String", "MEDIA_BUCKET", "\"${secret("MEDIA_BUCKET", "package-media")}\"")
        buildConfigField("String", "PROFILE_BUCKET", "\"${secret("PROFILE_BUCKET", "profiles")}\"")
        // Nexxauth — identity provider. Base URL includes the platform slug:
        // https://auth.med.rw/master. Client key from the ANDROID client in the
        // Nexxauth console; org slug matches the organisation registered there.
        buildConfigField("String", "NEXXAUTH_BASE_URL", "\"${secret("NEXXAUTH_BASE_URL", "https://auth.med.rw/master")}\"")
        buildConfigField("String", "NEXXAUTH_CLIENT_ID", "\"${secret("NEXXAUTH_CLIENT_ID", "")}\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                // Kept under .gradle/ (not build/) so it survives `clean` — the decode
                // runs at configuration time and won't re-run on a configuration-cache
                // hit, so the file must outlive the build directory.
                val keystoreFile = rootProject.layout.projectDirectory
                    .dir(".gradle").file("keystores/release.jks").asFile
                if (!keystoreFile.exists()) {
                    try {
                        keystoreFile.parentFile?.mkdirs()
                        // MIME decoder tolerates the wrapped/base64 -w0 output pasted into a secret.
                        keystoreFile.writeBytes(Base64.getMimeDecoder().decode(sigKeystoreBase64))
                        // Private key material — restrict to the owner.
                        keystoreFile.setReadable(false, false)
                        keystoreFile.setWritable(true, true)
                        keystoreFile.setReadable(true, true)
                    } catch (e: Exception) {
                        throw GradleException(
                            "Failed to decode KEYSTORE_BASE64 into $keystoreFile — it must be a base64-encoded JKS/P12 keystore.",
                            e
                        )
                    }
                }
                storeFile = keystoreFile
                storePassword = sigKeystorePassword
                keyAlias = sigKeyAlias
                keyPassword = sigKeyPassword
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release")
            if (signingConfig == null) {
                logger.lifecycle("Release signing NOT configured (KEYSTORE_* secrets missing) — release APK will be unsigned.")
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

    // supabase — STORAGE ONLY (file uploads). Auth/Postgrest/Realtime are gone;
    // authentication is handled by Nexxauth.
    implementation(platform(libs.supabase.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.supabase.storage.kt)
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
            // Same source of truth as BuildConfig.GRAPHQL_URL
            endpointUrl.set(secret("GRAPHQL_URL", "https://api.med.rw/deliveries/graphql"))
            schemaFile.set(file("src/main/graphql/schema.graphqls"))
        }
    }
}