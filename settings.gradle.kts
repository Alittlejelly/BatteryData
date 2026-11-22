pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "My Application"
include(":app")

// NexioUI 以源码方式引入（third_party/NexioUI 是上游 tag 0.1.1 + 本地补丁）：
// 浮层下拉的面板圆角在第 1 帧小于 2×圆角半径，SoftGlass 的边光会糊成一条白边（"顶部闪烁"），
// 这个只能在库内部把半径按面板短边夹一下 —— aar 里改不了，所以改成组合构建。
includeBuild("third_party/NexioUI") {
    dependencySubstitution {
        substitute(module("com.github.HaoZai000.NexioUI:nexioui")).using(project(":nexioui"))
    }
}
