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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.Manifest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        viewBinding = true
        prefab = true
        buildConfig = true
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
            multiDexEnabled = true
            multiDexKeepProguard = file("multidex-config.pro")
        }
    }

    lint {
        disable += "MissingTranslation"
    }

    flavorDimensions += "default"
    productFlavors {
        create("emulator") { // variant dimension for create emulator
            buildConfigField("boolean", "FULL_EMULATOR", "true")
            versionNameSuffix = System.getenv("VERSION_SUFFIX")?.takeIf(String::isNotBlank)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("midlet") { // variant dimension for create android port from J2ME app source
            buildConfigField("boolean", "FULL_EMULATOR", "false")
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

    applicationVariants.configureEach {
        if (buildType.name == "debug" && flavorName == "emulator") {
            resValue("string", "app_name", "JL-Mod Plus Debug")
        }
        outputs.configureEach {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "${rootProject.name}_$versionName-$dirName.apk"
            }
        }
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
    implementation(libs.androidx.multidex)
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

    implementation(libs.acra.core) {
        // ACRA only needs AutoService's generated metadata at runtime. Keeping
        // the annotation processor on the runtime classpath breaks R8.
        exclude(group = "com.google.auto.service", module = "auto-service")
    }
    implementation(libs.ambilwarna)
    implementation(libs.filepicker)
    implementation(libs.pngj)
    implementation(libs.rx.android)

    testImplementation(libs.junit)
    testImplementation(libs.asm)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
