import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("keystore.properties")
if (signingPropertiesFile.isFile) {
    FileInputStream(signingPropertiesFile).use(signingProperties::load)
}

fun signingValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)?.takeIf(String::isNotBlank)
        ?: signingProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)

val releaseStoreFilePath = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val releaseSigningConfigured = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(":").contains("release", ignoreCase = true)
}
if (releaseTaskRequested && !releaseSigningConfigured) {
    error("Release signing is not configured. Create keystore.properties or set the ANDROID_KEYSTORE_* environment variables.")
}

// Only the APK attached to GitHub releases asks GitHub for newer versions; the release workflow passes
// -PgithubUpdateCheck=true. F-Droid signs its own builds, so the GitHub APK could not be installed over them,
// and debug and local builds have no use for the banner either.
val githubUpdateCheck = providers.gradleProperty("githubUpdateCheck").map(String::toBoolean).getOrElse(false)

android {
    namespace = "io.github.realtvop.pixelcardemulator"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.realtvop.pixelcardemulator"
        // Dynamic color needs Android 12, so the theme has no static fallback palette.
        minSdk = 35
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"

        buildConfigField("boolean", "GITHUB_UPDATE_CHECK", githubUpdateCheck.toString())
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                val storeFilePath = requireNotNull(releaseStoreFilePath)
                storeFile = if (File(storeFilePath).isAbsolute) {
                    file(storeFilePath)
                } else {
                    rootProject.file(storeFilePath)
                }
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.conscrypt.android)
    implementation(libs.libadb.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
