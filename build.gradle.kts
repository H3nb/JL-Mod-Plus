buildscript {
    dependencies {
        // AGP built-in Kotlin uses AGP's runtime KGP unless a higher version is supplied here.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.androidx.room3).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.compose.screenshot).apply(false)
    alias(libs.plugins.ksp).apply(false)
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

