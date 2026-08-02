/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.Manifest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.compose.compiler)
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

val versionProperties = Properties().also { properties ->
    rootProject.file("version.properties").inputStream().use(properties::load)
}

val appVersionName = requireNotNull(versionProperties.getProperty("VERSION_NAME")) {
    "VERSION_NAME is missing from version.properties"
}.trim().also { value ->
    require(Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$").matches(value)) {
        "VERSION_NAME must follow Semantic Versioning: $value"
    }
}
val appVersionCode = requireNotNull(versionProperties.getProperty("VERSION_CODE")) {
    "VERSION_CODE is missing from version.properties"
}.trim().toInt().also { value ->
    require(value in 1..2_100_000_000) { "VERSION_CODE must be between 1 and 2100000000" }
}

val signingProperties = Properties().also { properties ->
    rootProject.file("keystore.properties").takeIf(File::isFile)?.inputStream()?.use(properties::load)
}

fun signingValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)?.takeIf(String::isNotBlank)
        ?: signingProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)

val signingStorePath = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
val signingStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val signingKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val signingKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val releaseSigningReady = listOf(
    signingStorePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

if (System.getenv("REQUIRE_RELEASE_SIGNING") == "true" && !releaseSigningReady) {
    throw GradleException("Release signing is required, but one or more signing values are missing.")
}

android {
    compileSdk = rootProject.extra["compileSdk"] as Int
    ndkVersion = rootProject.extra["ndkVersion"] as String
    namespace = "io.github.h3nb.jlmodplus"

    sourceSets.getByName("test").resources.directories.add(
        project(":dexlib").projectDir.resolve("src/main/assets").absolutePath
    )

    defaultConfig {
        applicationId = "io.github.h3nb.jlmodplus"
        minSdk = rootProject.extra["minSdk"] as Int
        targetSdk = rootProject.extra["targetSdk"] as Int
        versionCode = appVersionCode
        versionName = appVersionName
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

    val releaseSigning = signingConfigs.create("release") {
        if (releaseSigningReady) {
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
            storeFile = rootProject.file(requireNotNull(signingStorePath))
            storePassword = signingStorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningReady) {
                signingConfig = releaseSigning
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isJniDebuggable = true
        }
    }

    lint {
        disable += "MissingTranslation"
    }

    flavorDimensions += "default"
    productFlavors {
        create("emulator") { // variant dimension for create emulator
            versionNameSuffix = System.getenv("VERSION_SUFFIX")?.takeIf(String::isNotBlank)
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
        include("arm64-v8a")
        isUniversalApk = false
    }

    externalNativeBuild.ndkBuild.path("src/main/cpp/Android.mk")

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
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
    implementation(libs.androidx.collection)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    kapt(libs.androidx.room.compiler)
    // Room's javac processor reads Kotlin 2.4 metadata; keep the matching
    // reader on the kapt classpath even though it is not a processor itself.
    kapt(libs.kotlin.metadata.jvm)
    implementation(libs.androidx.room.runtime)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    kapt(libs.google.auto.service)
    compileOnly(libs.google.auto.service.annotations)
    implementation(libs.google.gson)
    implementation(libs.google.oboe)

    implementation(libs.acra.core) {
        // ACRA only needs AutoService's generated metadata at runtime. Keeping
        // the annotation processor on the runtime classpath breaks R8.
        exclude(group = "com.google.auto.service", module = "auto-service")
    }
    implementation(libs.pngj)

    testImplementation(libs.junit)
    testImplementation(libs.asm)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
