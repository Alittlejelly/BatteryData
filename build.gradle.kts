// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 内置的 KGP 是 2.2.10，而 MIUIX 0.9.4 由 Kotlin 2.4 编译，
        // 需要在 classpath 上显式抬高 Kotlin 编译器版本。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
