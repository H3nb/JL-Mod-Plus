import com.android.build.api.variant.BuildConfigField

plugins {
    id("com.android.library")
}

android {
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    namespace = "ru.playsoftware.j2meloader.dexlib"
    enableKotlin = false

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    buildFeatures.buildConfig = true

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
    }
}

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.put(
            "VERSION_CODE",
            BuildConfigField(
                type = "int",
                value = "1",
                comment = "JL-Mod Plus dexlib version code"
            )
        )
    }
}

dependencies {
    implementation(fileTree("dir" to "libs", "include" to listOf("*.jar")))
    api(libs.zip4j)
    implementation(libs.asm)

    testImplementation(libs.junit)
}
