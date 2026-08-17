import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.ResValue
import java.util.Locale
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.Manifest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.screenshot)
}

val secret = Properties().also { properties ->
    rootProject.file("keystore.properties").runCatching { inputStream().use(properties::load) }
}
// CI restores this key from ANDROID_DEBUG_KEYSTORE_BASE64. Never reuse it for release.
val sharedDebugKeystore = rootProject.file("debug.keystore")
val hasSharedDebugKeystore = sharedDebugKeystore.isFile
val runtimeTestAbi = providers.gradleProperty("jlmodRuntimeTestAbi").orNull
require(runtimeTestAbi == null || runtimeTestAbi == "arm64-v8a" || runtimeTestAbi == "x86_64") {
    "jlmodRuntimeTestAbi must be arm64-v8a or x86_64"
}
val diagnosticBuildCommit = (
    providers.gradleProperty("jlmodBuildCommit").orNull
        ?: System.getenv("JLMOD_BUILD_COMMIT")
        ?: System.getenv("GITHUB_SHA")
        ?: "unknown"
).trim().let { value ->
    if (value.matches(Regex("[0-9a-fA-F]{7,40}"))) value.lowercase(Locale.ROOT) else "unknown"
}

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    compileSdk = rootProject.extra["compileSdk"] as Int
    ndkVersion = rootProject.extra["ndkVersion"] as String
    namespace = "ru.playsoftware.j2meloader"

    defaultConfig {
        applicationId = "io.github.h3nb.jlmodplus"
        minSdk = rootProject.extra["minSdk"] as Int
        targetSdk = rootProject.extra["targetSdk"] as Int
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "app_name", "JL-Mod Plus")
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    @Suppress("UnstableApiUsage")
    androidResources.generateLocaleConfig = true

    buildFeatures {
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
        // Missing translations are intentionally deferred to the dedicated localization pass.
        // Keep lint active so all other findings remain visible and fail the CI task on errors.
        disable += "MissingTranslation"
    }

    flavorDimensions += "default"
    productFlavors {
        create("emulator") { // variant dimension for create emulator
            versionNameSuffix = System.getenv("VERSION_SUFFIX")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("midlet") { // variant dimension for create android port from J2ME app source
            // configure midlet's port project params here, as default it read from app manifest,
            // placed to 'app/src/midlet/resources/MIDLET-META-INF/MANIFEST.MF'
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

// Keep the legacy standalone MIDlet-to-APK source set available as reference, but do not
// create build variants for it unless porting support is intentionally re-enabled.
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
            "JLMOD_BUILD_COMMIT",
            BuildConfigField(
                type = "String",
                value = "\"$diagnosticBuildCommit\"",
                comment = "Source commit embedded for local diagnostic reproduction"
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
    annotationProcessor(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.rxjava2)
    implementation(libs.google.gson)
    implementation(libs.google.oboe)

    implementation(libs.acra.core) {
        exclude(group = "com.google.auto.service", module = "auto-service")
    }
    implementation(libs.ffmpeg.kit)
    implementation(libs.pngj)
    implementation(libs.rx.android)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    screenshotTestImplementation(libs.screenshot.validation.api)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
