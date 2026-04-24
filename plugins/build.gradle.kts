@file:Suppress("UnstableApiUsage")

import com.aliucord.gradle.AliucordExtension
import com.android.build.gradle.LibraryExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

subprojects {
    val libs = rootProject.libs

    apply {
        plugin(libs.plugins.android.library.get().pluginId)
        plugin(libs.plugins.aliucord.plugin.get().pluginId)
        plugin(libs.plugins.ktlint.get().pluginId)
    }

    configure<LibraryExtension> {
        namespace = "com.omnilm.loudmic"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        buildFeatures {
            buildConfig = true
            resValues = true
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    configure<AliucordExtension> {
        author("SonGokuKakarrot", 0L, hyperlink = false)
        github("https://github.com/SonGokuKakarrot/OmniLM")
    }

    configure<KtlintExtension> {
        version.set(libs.versions.ktlint.asProvider())
        coloredOutput.set(true)
        outputColorName.set("RED")
        ignoreFailures.set(true)
    }

    dependencies {
        compileOnly(libs.discord)
        compileOnly(libs.aliucord)
    }
}
