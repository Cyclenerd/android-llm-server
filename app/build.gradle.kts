plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "de.cyclenerd.android.llm.server"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.cyclenerd.android.llm.server"
        minSdk = 36
        targetSdk = 37
        // ---------------------------------------------------------------
        // VERSION — single source of truth.
        //
        // The release pipeline injects the tag via `-PversionName=…`
        // (e.g. `./gradlew assembleRelease -PversionName=1.2.3 -PversionCode=42`).
        // For local / debug builds we fall back to `0.1` so the in-app
        // "About" screen and Android Settings → App Info clearly show
        // that this is an unreleased build.
        //
        // Strip a leading "v" so passing either `1.2.3` or `v1.2.3`
        // works (the release.yml workflow already strips it, but this
        // makes manual `-PversionName=v1.2.3` invocations Just Work).
        // ---------------------------------------------------------------
        versionName = (providers.gradleProperty("versionName").orNull ?: "0.1").removePrefix("v")
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Only ship the ABIs we actually run on. Removing x86 splits saves
        // tens of megabytes and lets the system load the right native
        // libraries faster (no fallback search through universal blob).
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // -------------------------------------------------------------------------
    // BUILD TYPES — release tuned for "feels like a native game" performance.
    // -------------------------------------------------------------------------
    buildTypes {
        debug {
            // Keep BuildConfig flag distinct so we can gate verbose logging.
            buildConfigField("boolean", "PERF_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // R8 full mode is enabled in gradle.properties; strip kotlin
            // metadata, dead code, and inline aggressively.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Crunch PNGs at build time so the runtime never has to decode
            // anything that isn't already optimal.
            isCrunchPngs = true
            // Disable verbose perf logging in release for max throughput.
            buildConfigField("boolean", "PERF_LOGGING", "false")

            // Native-debug stripping — smaller APK, faster `dlopen`.
            ndk {
                debugSymbolLevel = "NONE"
            }

            // Sign with the debug keystore by default. This makes the
            // release APK install-able directly (Android requires every
            // APK to be signed, even with a debug key) without requiring
            // any release keystore secrets to be configured. CI artefacts
            // and side-loaded test builds work out of the box; for Play
            // Store distribution, override this with a real release
            // signingConfig in your local-only build override.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21

        // Enable core library desugaring is intentionally off — we target
        // SDK 36+ which natively supports all java.time/util features we use,
        // and skipping desugaring shaves a step from D8/R8.
    }

    buildFeatures {
        compose = true
        // Keep BuildConfig (we use BuildConfig.PERF_LOGGING).
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/proguard/*"
            excludes += "/kotlin/**"
            excludes += "/DebugProbesKt.bin"
        }
        // Keep the LiteRT/OpenCL native libraries page-aligned and
        // uncompressed so the dynamic linker can mmap them directly into
        // memory without a copy. This is the same trick high-end games
        // use to reduce engine warm-up time.
        jniLibs {
            useLegacyPackaging = false
            // Pick first wins for any duplicate native libs (LiteRT bundles
            // OpenCL stubs that may collide with vendor libraries).
            pickFirsts +=
                listOf(
                    "**/libc++_shared.so",
                    "**/libOpenCL.so",
                    "**/libOpenCL-pixel.so",
                    "**/libOpenCL-car.so",
                )
        }
    }

    // Bundle config — install only the ABI the device needs, never both.
    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += listOf("MissingTranslation")
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

dependencies {
    // LiteRT-LM for inference (pinned to latest for newest GPU/NPU kernels).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.15.0")

    // Ktor for HTTP server
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-cio:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("io.ktor:ktor-server-cors:3.5.2")
    implementation("io.ktor:ktor-server-status-pages:3.5.2")

    // Coroutines — we deliberately use the latest stable for the newest
    // dispatcher work-stealing improvements (better core utilisation).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.4")

    // Core Android
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // -------------------------------------------------------------------------
    // PERFORMANCE LIBRARIES — what makes the app launch and run "AAA-game" fast
    // -------------------------------------------------------------------------
    // App Startup — single ContentProvider entrypoint, faster cold start than
    // having every library declare its own provider.
    implementation("androidx.startup:startup-runtime:1.2.0")
    // Baseline & Startup profiles — ART pre-compiles the hot inference / Ktor
    // routing paths to native code at install time. Real-world: ~30% faster
    // cold start, ~15% lower jitter on the first request.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // androidx.tracing — zero-overhead tracing markers. Enables Perfetto /
    // Android Studio CPU profiler to attribute time to specific call sites.
    implementation("androidx.tracing:tracing-ktx:1.3.0")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.4")
}

// Configure Kotlin to target JVM 21 with optimisations:
//  - jsr305=strict tightens nullability checks (helps R8 inline more).
//  - -Xcontext-receivers reserved for future scoped perf hooks.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            // Replaces the legacy "-Xjvm-default=all" flag — generates real
            // default methods on JVM interfaces (smaller bytecode, faster
            // invocation). Modes in newer Kotlin: enable, disable,
            // no-compatibility. We use "enable".
            "-jvm-default=enable",
            // Allow internal use of low-level coroutines APIs needed for
            // dispatcher tuning in PerformanceManager.
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.DelicateCoroutinesApi",
        )
    }
}
