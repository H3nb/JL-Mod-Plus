import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.ResValue
import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.concurrent.withLock
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

@CacheableTask
abstract class GenerateBuildIdentityResourceTask : DefaultTask() {
    @get:Input
    abstract val buildCommit: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val valuesDirectory = outputDirectory.get().dir("values").asFile
        valuesDirectory.mkdirs()
        valuesDirectory.resolve("jlmod-build-identity.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="jlmod_build_commit" translatable="false">${buildCommit.get()}</string>
            </resources>
            """.trimIndent() + "\n"
        )
    }
}

abstract class NativeAudioSourcePreparationService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val lock = ReentrantLock()
    private var prepared = false

    fun prepareOnce(action: () -> Unit) {
        lock.withLock {
            if (prepared) return
            action()
            prepared = true
        }
    }

    override fun close() = Unit
}

@CacheableTask
abstract class BuildNativeAudioDependenciesTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val script: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val recipeFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourcePreparationScript: RegularFileProperty

    @get:Input
    abstract val abi: Property<String>

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val androidApi: Property<Int>

    @get:Input
    abstract val hostOs: Property<String>

    @get:Input
    abstract val hostArch: Property<String>

    @get:Internal
    abstract val sdkDirectory: DirectoryProperty

    @get:Internal
    abstract val dependencyRoot: DirectoryProperty

    @get:Internal
    abstract val sourcePreparationService: Property<NativeAudioSourcePreparationService>

    @get:Internal
    abstract val sourcesDirectory: DirectoryProperty

    @get:LocalState
    abstract val workDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val installDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        sourcePreparationService.get().prepareOnce {
            execOperations.exec {
                commandLine(
                    "pwsh", "-NoProfile", "-File", sourcePreparationScript.get().asFile,
                    "-OutRoot", sourcesDirectory.get().asFile,
                    "-Manifest", sourceManifest.get().asFile,
                    "-ForceExtract"
                )
            }
        }
        execOperations.exec {
            commandLine(
                "pwsh", "-NoProfile", "-File", script.get().asFile,
                "-OutRoot", dependencyRoot.get().asFile,
                "-WorkRoot", workDirectory.get().asFile,
                "-SourcesRoot", sourcesDirectory.get().asFile,
                "-Sdk", sdkDirectory.get().asFile,
                "-NdkVersion", ndkVersion.get(),
                "-AndroidApi", androidApi.get().toString(),
                "-Abis", abi.get(),
                "-SourcesAlreadyPrepared"
            )
        }
    }
}

abstract class VerifyNativePackagingTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val abi: Property<String>

    @TaskAction
    fun verify() {
        val checkedAbi = abi.get()
        val builtArtifacts = builtArtifactsLoader.get().load(apkDirectory.get())
            ?: error("Cannot load APK artifact metadata")
        val forbiddenLibraries = listOf(
            "libjlmem.so", "libjlmem_target.so", "libmmapi_tsf.so", "libmmapi_common.so",
            "libffmpegkit.so", "libffmpegkit_abidetect.so", "libavdevice.so", "libavfilter.so", "libswscale.so",
            "libavdevice_neon.so", "libavfilter_neon.so", "libswscale_neon.so"
        )
        var checkedApks = 0
        builtArtifacts.elements.forEach { artifact ->
            val apk = File(artifact.outputFile)
            ZipFile(apk).use { archive ->
                val abiPrefix = "lib/$checkedAbi/"
                if (archive.entries().asSequence().none { it.name.startsWith(abiPrefix) }) return@use
                checkedApks++
                forbiddenLibraries.forEach { library ->
                    check(archive.getEntry("$abiPrefix$library") == null) {
                        "${apk.name} still contains retired native library $abiPrefix$library"
                    }
                }
            }
        }
        check(checkedApks > 0) {
            "No APK artifact contains native libraries for $checkedAbi"
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room3)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.screenshot)
    alias(libs.plugins.ksp)
}

val rootDirectory = layout.projectDirectory.dir("..")
val keystorePropertiesFile = rootDirectory.file("keystore.properties")
val secret = providers.fileContents(keystorePropertiesFile).asText.orElse("").map { content ->
    Properties().apply {
        if (content.isNotEmpty()) content.reader().use { load(it) }
    }
}.get()
// CI restores this key from ANDROID_DEBUG_KEYSTORE_BASE64. Local setups can set
// debugStoreFile in keystore.properties to keep the shared debug key outside the checkout.
val debugKeystorePath = secret.getProperty("debugStoreFile")?.trim()?.takeIf { it.isNotEmpty() }
    ?: "debug.keystore"
val sharedDebugKeystore = rootDirectory.file(debugKeystorePath).asFile
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
val diagnosticBuildCommit = providers.gradleProperty("jlmodBuildCommit")
    .orElse(providers.environmentVariable("JLMOD_BUILD_COMMIT"))
    .orElse("unknown")
    .map { rawValue ->
        rawValue.trim().let { value ->
            if (value.matches(Regex("[0-9a-fA-F]{7,40}"))) value.lowercase(Locale.ROOT) else "unknown"
        }
    }
// Explicit opt-out for CI modes that never consume native build outputs; normal builds default to true.
val nativeBuildEnabled = providers.gradleProperty("jlmodNativeBuild")
    .orElse("true")
    .map { rawValue ->
        when (rawValue.trim().lowercase(Locale.ROOT)) {
            "true" -> true
            "false" -> false
            else -> error("jlmodNativeBuild must be true or false")
        }
    }
    .get()

val compileSdkVersion = libs.versions.androidCompileSdk.get().toInt()
val minSdkVersion = libs.versions.androidMinSdk.get().toInt()
val targetSdkVersion = libs.versions.androidTargetSdk.get().toInt()
val selectedNdkVersion = libs.versions.androidNdk.get()
val audioDependenciesRoot = layout.buildDirectory.dir("audio-deps")
val audioSourcesRoot = layout.buildDirectory.dir("audio-sources")
val nativeRecipeFiles = fileTree(rootDirectory.dir("tools/audio")) {
    include("build-opencore.ps1", "install-public-headers.ps1")
}
val audioDependencyTasks = if (nativeBuildEnabled) {
    val nativeAudioSourcePreparation = gradle.sharedServices.registerIfAbsent(
        "nativeAudioSourcePreparation",
        NativeAudioSourcePreparationService::class
    ) {}
    listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").associateWith { abi ->
        tasks.register<BuildNativeAudioDependenciesTask>("buildNativeAudio${abi.replace("-", "").replace("_", "")}") {
            script.set(rootDirectory.file("tools/audio/build-native-deps.ps1"))
            recipeFiles.from(nativeRecipeFiles)
            sourceManifest.set(rootDirectory.file("tools/audio/native-sources.json"))
            sourcePreparationScript.set(rootDirectory.file("tools/audio/prepare-native-sources.ps1"))
            this.abi.set(abi)
            ndkVersion.set(selectedNdkVersion)
            androidApi.set(minSdkVersion)
            hostOs.set(providers.systemProperty("os.name"))
            hostArch.set(providers.systemProperty("os.arch"))
            sdkDirectory.set(androidComponents.sdkComponents.sdkDirectory)
            dependencyRoot.set(audioDependenciesRoot)
            sourcePreparationService.set(nativeAudioSourcePreparation)
            usesService(nativeAudioSourcePreparation)
            sourcesDirectory.set(audioSourcesRoot)
            workDirectory.set(layout.buildDirectory.dir("audio-work/$abi"))
            installDirectory.set(audioDependenciesRoot.map { it.dir("install-$abi") })
        }
    }
} else {
    emptyMap()
}

// AGP exposes public artifact/source APIs, but no public task-provider hook for an
// ndk-build prebuilt prerequisite. Keep the unavoidable task-name boundary here;
// never replace it with AGP implementation classes.
if (nativeBuildEnabled) {
    tasks.configureEach {
        if (name.startsWith("configureNdkBuild") || name.startsWith("buildNdkBuild")) {
            audioDependencyTasks.forEach { (abi, build) ->
                if (name.endsWith("[$abi]")) dependsOn(build)
            }
        }
    }
}

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    compileSdk = compileSdkVersion
    if (nativeBuildEnabled) {
        ndkVersion = selectedNdkVersion
    }
    namespace = "io.github.h3nb.jlmodplus"

    defaultConfig {
        applicationId = "io.github.h3nb.jlmodplus"
        minSdk = minSdkVersion
        targetSdk = targetSdkVersion
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "app_name", "JL-Mod Plus")
        // Per-commit provenance is generated lazily through the public variant Sources API below.
        // Keep it out of configuration-time resValue/BuildConfig inputs so Configuration Cache can
        // survive a JLMOD_BUILD_COMMIT change without invalidating Kotlin/Java compilation.
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (nativeBuildEnabled) {
            externalNativeBuild.ndkBuild.arguments += "JLMOD_AUDIO_DEPS=${audioDependenciesRoot.get().asFile.absolutePath.replace('\\', '/')}"
        }
    }

    @Suppress("UnstableApiUsage")
    androidResources.generateLocaleConfig = true

    buildFeatures {
        aidl = true
        compose = true
        prefab = nativeBuildEnabled
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
            storeFile = rootDirectory.file(secret.getProperty("storeFile")).asFile
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
            if (nativeBuildEnabled) ndk {
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
            versionNameSuffix = providers.environmentVariable("VERSION_SUFFIX").orNull
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("midlet") {
            val props = getMidletManifestProperties()
            val midletName = props.getValue("MIDlet-Name")?.trim() ?: "Demo MIDlet"
            val apkName = midletName.replace("[/\\\\:*?\"<>|]".toRegex(), "").replace(" ", "_")
            applicationId = "com.example.androidlet.${apkName.lowercase(Locale.ROOT)}"
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

    if (nativeBuildEnabled) {
        externalNativeBuild.ndkBuild.path("src/main/cpp/Android.mk")
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
        val generateBuildIdentityResource = tasks.register<GenerateBuildIdentityResourceTask>(
            "generate${variant.name.replaceFirstChar { it.uppercaseChar() }}BuildIdentityResource"
        ) {
            buildCommit.set(diagnosticBuildCommit)
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            generateBuildIdentityResource,
            GenerateBuildIdentityResourceTask::outputDirectory
        )

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
            if (nativeBuildEnabled) {
                val verifyNativePackaging = tasks.register<VerifyNativePackagingTask>(
                    "verifyEmulatorDebugNativePackaging"
                ) {
                    abi.set(runtimeTestAbi ?: "arm64-v8a")
                    builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
                }
                variant.artifacts.use(verifyNativePackaging).wiredWith {
                    it.apkDirectory
                }.toListenTo(SingleArtifact.APK)
            }
        }
    }
}

// The verifier above listens to AGP's public APK artifact, so it follows the actual
// produced outputs without depending on package task names or output-directory conventions.
fun getMidletManifestProperties(): Attributes {
    val manifestFile = layout.projectDirectory.file("src/midlet/resources/MIDLET-META-INF/MANIFEST.MF")
    val content = providers.fileContents(manifestFile).asText.orElse("").get()
    return Manifest().apply {
        if (content.isNotEmpty()) content.byteInputStream().use { read(it) }
    }.mainAttributes
}

dependencies {
    implementation(project(":dexlib"))

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
