// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

extra.set("compileSdk", 36)
extra.set("minSdk", 23)
extra.set("targetSdk", 36)
extra.set("ndkVersion", "28.2.13676358")
