import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.navigation.safeargs.kotlin)
    alias(libs.plugins.detekt)
}

// Get git commit short code
fun getGitCommitShort(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.dopple.webview"
    compileSdk = 34

    signingConfigs {
        create("dev") {
            // Load signing properties from shared orbital keystores, local.properties, or env.
            val props = Properties()
            val keystoreDirs = orbitalShareDirs().map { "$it/keystores" }
            val sharedProps = keystoreDirs.map { File(it + "/aosp.properties") }.firstOrNull { it.exists() }
            if (sharedProps != null) {
                props.load(sharedProps.inputStream())
            }
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                props.load(localPropertiesFile.inputStream())  // local.properties overrides shared
            }

            // AOSP platform key for signature-level permission compatibility
            val storePath = props.getProperty("DEV_STORE_FILE")
                ?: System.getenv("DEV_STORE_FILE")
                ?: "aosp.keystore.jks"
            // Resolve keystore: check shared dirs first, then project root
            val resolvedStore = if (File(storePath).isAbsolute) {
                File(storePath)
            } else {
                keystoreDirs.map { File(it + "/" + storePath) }.firstOrNull { it.exists() }
                    ?: rootProject.file(storePath)
            }
            storeFile = resolvedStore
            storePassword = props.getProperty("DEV_STORE_PASSWORD")
                ?: System.getenv("DEV_STORE_PASSWORD")
                ?: "dopple-works"
            keyAlias = props.getProperty("DEV_KEY_ALIAS")
                ?: System.getenv("DEV_KEY_ALIAS")
                ?: "platform"
            keyPassword = props.getProperty("DEV_KEY_PASSWORD")
                ?: System.getenv("DEV_KEY_PASSWORD")
                ?: "loop"
        }
    }

    defaultConfig {
        applicationId = "com.dopple.webview"
        minSdk = 34
        targetSdk = 34
        versionCode = 4
        versionName = "0.0.4"

        // Set the base APK name
        setProperty("archivesBaseName", "native-webview-${getGitCommitShort()}")

        // Android Test configuration
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Love2D pre-built .so files are loaded via jniLibs (see sourceSets below).
        // No CMake needed — lovelace builds liblove.so via scripts/build-android.sh.
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("dev")
        }
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // Dependency upgrade suggestions are intentional decisions, not lint violations
        disable += "GradleDependency"
        disable += "AndroidGradlePluginVersion"
        // Keeping targetSdk=34 per project requirements
        disable += "OldTargetApi"
        // Third-party library (mlkit barcode-scanning); can't fix, await upstream update
        disable += "Aligned16KB"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Love2D native libs: pre-built by lovelace's scripts/build-android.sh
    // Resolved from shared orbital volume (namespaced), local.properties LOVE_DIR, or sibling love repo.
    sourceSets {
        getByName("main") {
            val props = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                props.load(localPropertiesFile.inputStream())
            }
            val jniDirs = orbitalShareDirs().map { "$it/jniLibs/love-android" }
            val loveDirProp = props.getProperty("LOVE_DIR")
            val jniSrcDir = when {
                loveDirProp != null -> rootProject.file("$loveDirProp/dist/android/jniLibs")
                else -> jniDirs.map { File(it) }.firstOrNull { File(it, "arm64-v8a/liblove.so").exists() }
                    ?: rootProject.file("../../../love/crew/lovelace/dist/android/jniLibs")
            }
            jniLibs.srcDir(jniSrcDir)
        }
    }
}

detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = true
}

tasks.register<Copy>("installGitHooks") {
    from("${rootProject.rootDir}/scripts/pre-commit")
    into("${rootProject.rootDir}/.git/hooks")
    fileMode = 0b111101101 // 755
}

tasks.register<Exec>("buildLoopSdk") {
    description = "Build Loop SDK (rollup → loop-sdk.js)"
    workingDir = rootProject.rootDir
    commandLine("npm", "run", "build")
    // Only re-run when SDK sources change
    inputs.files(fileTree("${rootProject.rootDir}/sdk") { exclude("**/*.d.ts") })
    inputs.file("${rootProject.rootDir}/bridge-contract.yaml")
    inputs.file("${rootProject.rootDir}/rollup.config.mjs")
    inputs.file("${rootProject.rootDir}/tsconfig.json")
    outputs.file("${rootProject.rootDir}/app/src/main/assets/loop-sdk.js")
}

tasks.register<Exec>("buildGallery") {
    description = "Build gallery React app (Vite → assets/gallery/)"
    workingDir = file("../gallery")
    commandLine("npm", "run", "build")
    inputs.dir("../gallery/src")
    inputs.file("../gallery/package.json")
    inputs.file("../gallery/vite.config.ts")
    inputs.file("../gallery/tsconfig.json")
    inputs.file("../gallery/index.html")
    outputs.dir("src/main/assets/gallery")
}

// Derive orbital shared volume root from workspace path.
// Container: /home/claude/orbital/, Host: parent of workspaces/ dir (e.g. ~/orbital-data/)
fun orbitalShareDirs(): List<String> {
    val dirs = mutableListOf("/home/claude/orbital")
    // Detect host orbital-data root: walk up from project dir looking for workspaces/ sibling
    var dir = rootProject.projectDir.parentFile
    while (dir != null) {
        if (File(dir, "workspaces").isDirectory) {
            dirs.add(dir.absolutePath)
            break
        }
        dir = dir.parentFile
    }
    return dirs
}

tasks.register("logLoveDeps") {
    description = "Log Love2D dependency version from shared orbital manifest"
    doLast {
        val manifestDirs = orbitalShareDirs().map { "$it/jniLibs/love-android" }
        val manifest = manifestDirs.map { File(it + "/manifest.json") }.firstOrNull { it.exists() }
        if (manifest != null) {
            val json = groovy.json.JsonSlurper().parseText(manifest.readText()) as Map<*, *>
            val commit = json["commit"] ?: "unknown"
            val branch = json["branch"] ?: "unknown"
            val timestamp = json["timestamp"] ?: "unknown"
            logger.lifecycle("Love2D libs: $commit ($branch, $timestamp)")
        } else {
            logger.warn("WARNING: Love2D manifest.json not found in shared volume — .so version unknown")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("installGitHooks", "buildLoopSdk", "buildGallery", "logLoveDeps")
}

dependencies {
    // Services AIDL from dopple-android (freshly built AAR)
    implementation(files("libs/services-aidl-release.aar"))
    // Settings library from LoopSettings project
    implementation(files("libs/settings-library-release.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit Barcode Scanning
    implementation(libs.mlkit.barcode.scanning)

    // OkHttp
    implementation(libs.okhttp)

    // Gson
    implementation(libs.gson)

    // NanoHTTPD
    implementation(libs.nanohttpd)

    // Glide (for GIF support)
    implementation(libs.glide)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Nordic BLE
    implementation(libs.nordic.ble.ktx)

    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.fragment.testing.manifest)
    testImplementation(libs.androidx.fragment.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)

    // Instrumented Testing (androidTest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.web)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
