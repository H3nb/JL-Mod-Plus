import com.android.build.api.variant.BuildConfigField

plugins {
    id("com.android.library")
}

android {
    compileSdk = rootProject.extra["compileSdk"] as Int
    namespace = "ru.playsoftware.j2meloader.dexlib"
    enableKotlin = false

    defaultConfig {
        minSdk = rootProject.extra["minSdk"] as Int
    }

    buildFeatures.buildConfig = true

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        targetSdk = rootProject.extra["targetSdk"] as Int
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
    api(libs.zip4j)
    implementation(libs.asm)
}
