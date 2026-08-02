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

import com.android.build.api.variant.BuildConfigField

plugins {
    id("com.android.library")
}

android {
    enableKotlin = false
    compileSdk = rootProject.extra["compileSdk"] as Int
    namespace = "io.github.h3nb.jlmodplus.dexlib"

    defaultConfig {
        minSdk = rootProject.extra["minSdk"] as Int
    }

    buildFeatures.buildConfig = true

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }

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
    implementation(libs.asm.commons)
}
