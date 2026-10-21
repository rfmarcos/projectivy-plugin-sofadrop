import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Local, never committed settings (local.properties):
//  - tmdb.token / mdblist.key: personal keys baked into *debug* builds only
//  - trakt.clientId / trakt.clientSecret / mdblist.clientId / tmdb.appKey: the *app's* credentials,
//    included in every build so users only scan QR codes (nothing to type on the TV)
//  - release.storeFile / release.storePassword / release.keyAlias / release.keyPassword: release signing
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProperty(name: String) = localProperties.getProperty(name, "").replace("\"", "").trim()
val defaultTmdbToken = localProperty("tmdb.token")
val defaultMdbListKey = localProperty("mdblist.key")

android {
    namespace = libs.versions.appId.get()
    compileSdk = libs.versions.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = libs.versions.appId.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEFAULT_TRAKT_CLIENT_ID", "\"${localProperty("trakt.clientId")}\"")
        buildConfigField("String", "DEFAULT_TRAKT_CLIENT_SECRET", "\"${localProperty("trakt.clientSecret")}\"")
        buildConfigField("String", "MDBLIST_CLIENT_ID", "\"${localProperty("mdblist.clientId")}\"")
        buildConfigField("String", "TMDB_APP_KEY", "\"${localProperty("tmdb.appKey")}\"")
        // Links shown in "About" (hidden when empty)
        buildConfigField("String", "SUPPORT_URL", "\"${localProperty("support.url")}\"")
        buildConfigField("String", "SOURCE_URL", "\"${localProperty("source.url")}\"")
    }

    signingConfigs {
        localProperties.getProperty("release.storeFile")?.let { storePath ->
            create("release") {
                storeFile = file(storePath)
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "DEFAULT_TMDB_TOKEN", "\"$defaultTmdbToken\"")
            buildConfigField("String", "DEFAULT_MDBLIST_KEY", "\"$defaultMdbListKey\"")
        }
        getByName("release") {
            // Published APKs never contain personal keys: every user enters their own
            buildConfigField("String", "DEFAULT_TMDB_TOKEN", "\"\"")
            buildConfigField("String", "DEFAULT_MDBLIST_KEY", "\"\"")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(libs.zxing.core)
    implementation(project(":api"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
