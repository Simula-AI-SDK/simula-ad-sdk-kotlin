plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "ad.simula.ad.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "ad.simula"
                artifactId = "ad-sdk"
                version = findProperty("VERSION_NAME")?.toString() ?: "1.0.0"

                pom {
                    name.set("Simula Ad SDK")
                    description.set("Simula interactive ad SDK for Android")
                    url.set("https://github.com/AugustBemworworthy/simula-ad-sdk-kotlin") // TODO: replace with your repo URL

                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }

                    developers {
                        developer {
                            id.set("simula")          // TODO: your ID
                            name.set("Simula")        // TODO: your name
                            email.set("dev@simula.ad") // TODO: your email
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/YOUR_USERNAME/simula-ad-sdk-kotlin.git")       // TODO
                        developerConnection.set("scm:git:ssh://github.com/YOUR_USERNAME/simula-ad-sdk-kotlin.git") // TODO
                        url.set("https://github.com/YOUR_USERNAME/simula-ad-sdk-kotlin")                           // TODO
                    }
                }
            }
        }

        repositories {
            maven {
                name = "sonatype"
                val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                url = if ((findProperty("VERSION_NAME")?.toString() ?: "").endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl

                credentials {
                    username = findProperty("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME") ?: ""
                    password = findProperty("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD") ?: ""
                }
            }
        }
    }

    signing {
        // Uses GPG key from gradle.properties or env vars
        val signingKeyId = findProperty("signing.keyId")?.toString() ?: System.getenv("SIGNING_KEY_ID")
        val signingKey = findProperty("signing.key")?.toString() ?: System.getenv("SIGNING_KEY")
        val signingPassword = findProperty("signing.password")?.toString() ?: System.getenv("SIGNING_PASSWORD")

        if (signingKey != null) {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        }

        sign(publishing.publications["release"])
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.runtime:runtime")

    // Activity Compose (for BackHandler)
    implementation("androidx.activity:activity-compose:1.8.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // WebView
    implementation("androidx.webkit:webkit:1.10.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
}
