import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.ResValue
import java.util.Locale
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room3)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.screenshot)
    alias(libs.plugins.ksp)
}

val secret = Properties().also { properties ->
    rootProject.file("keystore.properties").runCatching { inputStream().use(properties::load) }
}
// CI restores this key from ANDROID_DEBUG_KEYSTORE_BASE64. Local setups can set
// debugStoreFile in keystore.properties to keep the shared debug key outside the checkout.
val debugKeystorePath = secret.getProperty("debugStoreFile")?.trim()?.takeIf { it.isNotEmpty() }
    ?: "debug.keystore"
val sharedDebugKeystore = rootProject.file(debugKeystorePath)
val hasSharedDebugKeystore = sharedDebugKeystore.isFile
val runtimeTestAbi = providers.gradleProperty("jlmodRuntimeTestAbi").orNull
require(runtimeTestAbi == null || runtimeTestAbi == "arm64-v8a" || runtimeTestAbi == "x86_64") {
    "jlmodRuntimeTestAbi must be arm64-v8a or x86_64"
}
val appVersionName = providers.gradleProperty("jlmod.versionName").get().trim()
val appVersionCode = providers.gradleProperty("jlmod.versionCode").get().toIntOrNull()
    ?: error("jlmod.versionCode must be an integer")
require(appVersionName.isNotEmpty()) { "jlmod.versionName must not be empty" }
require(appVersionCode > 0) { "jlmod.versionCode must be greater than zero" }
val diagnosticBuildCommit = (
    providers.gradleProperty("jlmodBuildCommit").orNull
        ?: System.getenv("JLMOD_BUILD_COMMIT")
        ?: "unknown"
).trim().let { value ->
    if (value.matches(Regex("[0-9a-fA-F]{7,40}"))) value.lowercase(Locale.ROOT) else "unknown"
}

val audioDependenciesRoot = layout.buildDirectory.dir("audio-deps")
val audioDependencyTasks = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").associateWith { abi ->
    tasks.register<Exec>("buildNativeAudio${abi.replace("-", "").replace("_", "")}") {
        inputs.files(rootProject.fileTree("tools/audio"))
        inputs.property("abi", abi)
        inputs.property("host", "${System.getProperty("os.name")}/${System.getProperty("os.arch")}")
        inputs.property("ndk", rootProject.extra["ndkVersion"] as String)
        outputs.dir(audioDependenciesRoot.map { it.dir("install-$abi") })
        outputs.cacheIf("Pinned native audio recipe is reusable for the same ABI, host, and NDK") { true }
        commandLine("pwsh", "-NoProfile", "-File", rootProject.file("tools/audio/build-native-deps.ps1"),
            "-OutRoot", audioDependenciesRoot.get().asFile, "-Sdk",
            androidComponents.sdkComponents.sdkDirectory.get().asFile,
            "-NdkVersion", rootProject.extra["ndkVersion"] as String, "-Abis", abi)
    }
}
tasks.configureEach {
    if (name.startsWith("configureNdkBuild") || name.startsWith("buildNdkBuild")) {
        audioDependencyTasks.forEach { (abi, build) -> if (name.endsWith("[$abi]")) dependsOn(build) }
    }
}

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    compileSdk = rootProject.extra["compileSdk"] as Int
    ndkVersion = rootProject.extra["ndkVersion"] as String
    namespace = "io.github.h3nb.jlmodplus"

    defaultConfig {
        applicationId = "io.github.h3nb.jlmodplus"
        minSdk = rootProject.extra["minSdk"] as Int
        targetSdk = rootProject.extra["targetSdk"] as Int
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "app_name", "JL-Mod Plus")
        // Keep per-commit provenance out of BuildConfig so it does not invalidate Kotlin/Java
        // compilation on every CI commit. Packaging builds set JLMOD_BUILD_COMMIT explicitly.
        resValue("string", "jlmod_build_commit", diagnosticBuildCommit)
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild.ndkBuild.arguments += "JLMOD_AUDIO_DEPS=${audioDependenciesRoot.get().asFile.absolutePath.replace('\\', '/')}"
    }

    @Suppress("UnstableApiUsage")
    androidResources.generateLocaleConfig = true

    buildFeatures {
        aidl = true
        compose = true
        prefab = true
        buildConfig = true
        resValues = true
    }

    signingConfigs.create("sharedDebug") {
        if (hasSharedDebugKeystore) {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = sharedDebugKeystore
            storePassword = "android"
        }
    }

    signingConfigs.create("emulator") {
        if (secret.isNotEmpty()) {
            keyAlias = secret.getProperty("keyAlias")
            keyPassword = secret.getProperty("keyPassword")
            storeFile = rootProject.file(secret.getProperty("storeFile"))
            storePassword = secret.getProperty("storePassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (secret.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("emulator")
            }
        }
        debug {
            if (hasSharedDebugKeystore) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
            applicationIdSuffix = ".debug"
            isJniDebuggable = true
            ndk {
                // Normal debug builds remain arm64-only. Hosted runtime tests opt into x86_64
                // explicitly so they can run on a Linux x86_64 Android Emulator.
                abiFilters += runtimeTestAbi ?: "arm64-v8a"
            }
        }
    }

    lint {
        // Analyze project dependencies through the app lint graph so library lint can be
        // scheduled with the app instead of requiring a second top-level lint invocation.
        checkDependencies = true
        // Missing translations are intentionally deferred to the dedicated localization pass.
        // Keep lint active so all other findings remain visible and fail the CI task on errors.
        disable += "MissingTranslation"
    }

    flavorDimensions += "default"
    productFlavors {
        create("emulator") {
            versionNameSuffix = System.getenv("VERSION_SUFFIX")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("midlet") {
            val props = getMidletManifestProperties()
            val midletName = props.getValue("MIDlet-Name")?.trim() ?: "Demo MIDlet"
            val apkName = midletName.replace("[/\\\\:*?\"<>|]".toRegex(), "").replace(" ", "_")
            applicationId = "com.example.androidlet.${apkName.lowercase(Locale.getDefault())}"
            versionName = props.getValue("MIDlet-Version") ?: "1.0"
            resValue("string", "app_name", midletName)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-midlet.pro"
            )
        }
    }

    splits.abi {
        isEnable = true
        reset()
        include("x86", "armeabi-v7a", "x86_64", "arm64-v8a")
        isUniversalApk = true
    }

    externalNativeBuild.ndkBuild.path("src/main/cpp/Android.mk")

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

androidComponents {
    beforeVariants(selector().withFlavor("default" to "midlet")) { variantBuilder ->
        variantBuilder.enable = false
    }

    onVariants { variant ->
        val fullEmulator = variant.flavorName == "emulator"
        variant.buildConfigFields?.put(
            "FULL_EMULATOR",
            BuildConfigField(
                type = "boolean",
                value = fullEmulator.toString(),
                comment = "Whether this is the full emulator flavor"
            )
        )
        variant.buildConfigFields?.put(
            "JLMOD_BUILD_VARIANT",
            BuildConfigField(
                type = "String",
                value = "\"${variant.name}\"",
                comment = "Android variant embedded for local diagnostic reproduction"
            )
        )

        if (variant.name == "emulatorDebug") {
            variant.resValues.put(
                variant.makeResValueKey("string", "app_name"),
                ResValue("JL-Mod Plus Debug", "Debug application name")
            )
        }
    }
}

// Reject retired native modules left by incremental builds in the install artifact.
val verifyEmulatorDebugNativePackaging = tasks.register("verifyEmulatorDebugNativePackaging") {
    dependsOn("packageEmulatorDebug")
    val abi = runtimeTestAbi ?: "arm64-v8a"
    val apk = layout.buildDirectory.file(
        "outputs/apk/emulator/debug/app-emulator-$abi-debug.apk",
    )
    inputs.property("abi", abi)
    inputs.file(apk)
    outputs.upToDateWhen { false }
    doLast {
        val checkedAbi = inputs.properties.getValue("abi").toString()
        val checkedApk = inputs.files.singleFile
        check(checkedApk.isFile) {
            "Expected emulator debug APK for $checkedAbi was not produced: ${checkedApk.absolutePath}"
        }
        val forbiddenLibraries = listOf(
            "libjlmem.so", "libjlmem_target.so", "libmmapi_tsf.so", "libmmapi_common.so",
            "libffmpegkit.so", "libffmpegkit_abidetect.so", "libavdevice.so", "libavfilter.so", "libswscale.so",
            "libavdevice_neon.so", "libavfilter_neon.so", "libswscale_neon.so"
        )
        ZipFile(checkedApk).use { archive ->
            forbiddenLibraries.forEach { library ->
                val entry = archive.getEntry("lib/$checkedAbi/$library")
                check(entry == null) {
                    "${checkedApk.name} still contains retired native library lib/$checkedAbi/$library"
                }
            }
        }
    }
}

tasks.configureEach {
    if (name == "assembleEmulatorDebug") {
        dependsOn(verifyEmulatorDebugNativePackaging)
    }
}

fun getMidletManifestProperties(): Attributes = Manifest().let { mf ->
    project.file("src/midlet/resources/MIDLET-META-INF/MANIFEST.MF").runCatching {
        inputStream().use(mf::read)
    }
    return mf.mainAttributes
}

dependencies {
    implementation(projects.dexlib)

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.navigation)
    implementation(libs.androidx.material3.adaptive.navigation3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.preference.ktx)

    // Library Architecture v2 (H3nb/JL-Mod-Plus#92).
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room3.compiler)

    implementation(libs.google.gson)
    implementation(libs.google.oboe)
    implementation(libs.pngj)
    implementation(libs.rx.android)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    screenshotTestImplementation(libs.screenshot.validation.api)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room3.testing)
    // Android local-unit-test configurations otherwise resolve sqlite-bundled's Android variant,
    // whose JNI binaries cannot load on the Linux host. Pin the dedicated JVM artifact only for
    // host DB execution; production continues to use AndroidSQLiteDriver/sqlite-framework.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
