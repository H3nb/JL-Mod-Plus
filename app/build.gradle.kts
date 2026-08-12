// Modified for JL-Mod Plus.
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.ResValue
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.Manifest

plugins {
    alias(libs.plugins.android.application)
}

/** Copies AGP's final APKs to a stable, human-readable distribution directory. */
abstract class CopyApk : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val archiveBaseName: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun copyApks() {
        val outputDirectory = output.get()
        outputDirectory.asFile.deleteRecursively()
        outputDirectory.asFile.mkdirs()

        val builtArtifacts = builtArtifactsLoader.get().load(input.get())
            ?: throw GradleException("Cannot load APKs for ${variantName.get()}")

        builtArtifacts.elements.forEach { artifact ->
            val versionName = artifact.versionName?.takeIf(String::isNotBlank) ?: "unspecified"
            val outputSuffix = artifact.filters.firstOrNull()?.identifier ?: variantName.get()
            val fileName = "${archiveBaseName.get()}_${versionName}-${outputSuffix}.apk"
            File(artifact.outputFile).copyTo(
                outputDirectory.file(fileName).asFile,
                overwrite = true
            )
        }

        builtArtifacts.save(outputDirectory)
    }
}

val secret = Properties().also { properties ->
    rootProject.file("keystore.properties").runCatching { inputStream().use(properties::load) }
}
val runtimeTestAbi = providers.gradleProperty("jlmodRuntimeTestAbi").orNull
require(runtimeTestAbi == null || runtimeTestAbi == "arm64-v8a" || runtimeTestAbi == "x86_64") {
    "jlmodRuntimeTestAbi must be arm64-v8a or x86_64"
}

android {
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
        viewBinding = true
        prefab = true
        buildConfig = true
        resValues = true
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
        }
        debug {
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
        baseline = file("lint-baseline.xml")
        disable += "MissingTranslation"
    }

    flavorDimensions += "default"
    productFlavors {
        create("emulator") { // variant dimension for create emulator
            signingConfig = signingConfigs.getByName("emulator")
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

        if (variant.name == "emulatorDebug") {
            variant.resValues.put(
                variant.makeResValueKey("string", "app_name"),
                ResValue("JL-Mod Plus Debug", "Debug application name")
            )
        }

        val taskSuffix = variant.name.replaceFirstChar { it.uppercaseChar() }
        val copyTask = tasks.register<CopyApk>("copy${taskSuffix}Apk") {
            archiveBaseName.set(rootProject.name)
            variantName.set(variant.name)
            output.set(layout.buildDirectory.dir("outputs/renamed_apks/${variant.name}"))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
        }
        variant.artifacts.use(copyTask).wiredWith { it.input }.toListenTo(SingleArtifact.APK)
    }
}

kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)

fun getMidletManifestProperties(): Attributes = Manifest().let { mf ->
    project.file("src/midlet/resources/MIDLET-META-INF/MANIFEST.MF").runCatching {
        inputStream().use(mf::read)
    }
    return mf.mainAttributes
}

dependencies {
    implementation(projects.dexlib)

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.arch.core.common)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    annotationProcessor(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.rxjava2)
    implementation(libs.androidx.transition)

    annotationProcessor(libs.google.auto.service)
    compileOnly(libs.google.auto.service.annotations)
    implementation(libs.google.gson)
    implementation(libs.google.material)
    implementation(libs.google.oboe)

    implementation(libs.acra.core)
    implementation(libs.ambilwarna)
    implementation(libs.ffmpeg.kit)
    implementation(libs.filepicker)
    implementation(libs.pngj)
    implementation(libs.rx.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
