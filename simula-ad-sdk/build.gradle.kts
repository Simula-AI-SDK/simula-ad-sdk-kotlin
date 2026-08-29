import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.vanniktech.maven.publish)
}

val sdkVersion = "1.2.0-dev.1"

android {
    namespace = "ad.simula.ad.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Published SDK: the library is intentionally NOT minified here. The
            // consuming app's R8 shrinks & obfuscates the SDK in its final build,
            // so pre-minifying the AAR gives no app-size benefit and would
            // obfuscate the public API (breaking consumers). Size is optimized
            // for consumers via the tight rules in consumer-rules.pro.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

// ── Maven Central Publishing ────────────────────────────────────────────────

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("ad.simula", "ad-sdk", sdkVersion)

    pom {
        name.set("Simula Ad SDK")
        description.set("Simula interactive ad SDK for Android")
        url.set("https://github.com/Simula-AI-SDK/simula-ad-sdk-kotlin")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("Simula-Ad")
                name.set("Simula Admin")
                email.set("admin@simula.ad")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/Simula-AI-SDK/simula-ad-sdk-kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/Simula-AI-SDK/simula-ad-sdk-kotlin.git")
            url.set("https://github.com/Simula-AI-SDK/simula-ad-sdk-kotlin")
        }
    }
}

val verifyVersionConsistency = tasks.register("verifyVersionConsistency") {
    group = "verification"
    description = "Verifies that Maven, telemetry, and public API versions match."

    val telemetryFile = file("src/main/kotlin/ad/simula/ad/sdk/telemetry/Telemetry.kt")
    val sdkInfoFile = file("src/main/kotlin/ad/simula/ad/sdk/SimulaAdSdk.kt")
    inputs.files(telemetryFile, sdkInfoFile)
    inputs.property("sdkVersion", sdkVersion)

    doLast {
        fun versionFrom(file: File, pattern: Regex, label: String): String =
            pattern.find(file.readText())?.groupValues?.get(1)
                ?: throw GradleException("Could not read $label version from ${file.path}")

        val telemetryVersion = versionFrom(
            telemetryFile,
            Regex("SIMULA_SDK_VERSION\\s*=\\s*\"([^\"]+)\""),
            "telemetry",
        )
        val publicApiVersion = versionFrom(
            sdkInfoFile,
            Regex("const val VERSION\\s*=\\s*\"([^\"]+)\""),
            "public API",
        )
        val versions = mapOf(
            "Maven" to sdkVersion,
            "telemetry" to telemetryVersion,
            "public API" to publicApiVersion,
        )
        if (versions.values.toSet().size != 1) {
            throw GradleException(
                "SDK versions must match: " + versions.entries.joinToString { "${it.key}=${it.value}" },
            )
        }
    }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(verifyVersionConsistency)
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.runtime)

    // Activity Compose (for BackHandler)
    implementation(libs.androidx.activity.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Networking (native HttpURLConnection via SimulaHttp — no third-party HTTP lib)
    implementation(libs.kotlinx.serialization.json)

    // Image loading: native ImageDecoder/BitmapFactory pipeline (see ad.simula.ad.sdk.image) — no third-party lib

    // WebView
    implementation(libs.androidx.webkit)

    // Core
    implementation(libs.androidx.core.ktx)

    // Unit tests (JVM). kotlinx-serialization-json is already on the test classpath
    // via the implementation dependency above.
    testImplementation(libs.junit)
    // Deterministic coroutine testing (runTest / StandardTestDispatcher / virtual time)
    // for the reward-verification queue engine. Version tracks kotlinx-coroutines-android.
    testImplementation(libs.kotlinx.coroutines.test)

    // Device tests exercise the actual SQLiteOpenHelper-backed durable row store.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
