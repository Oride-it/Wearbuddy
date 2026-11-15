import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigning = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}
fun signingValue(name: String): String =
    providers.environmentVariable("WEARBUDDY_" + name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase())
        .orNull ?: releaseSigning.getProperty(name).orEmpty()

android {
    namespace = "com.wear.wearbuddy"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.wear.wearbuddy"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"Oride-it/Wearbuddy\"")
        // Android 8.0 = API 26
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 12
        versionName = "1.0.11"
    }

    // Release 专用签名：手表侧载与覆盖升级需要固定签名
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signingValue("storeFile").ifBlank { "keystore/wearbuddy-release.jks" })
            storePassword = signingValue("storePassword")
            keyAlias = signingValue("keyAlias")
            keyPassword = signingValue("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Keep UI regression tests separate from the signed, installed app and its account.
            applicationIdSuffix = ".debug"
        }
        release {
            // 正式版：代码收缩 + 资源收缩 + R8 优化
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // 关于页显示版本号
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.protolayout)
    implementation(libs.androidx.protolayout.material3)
    implementation(libs.androidx.tiles)
    implementation(libs.androidx.tiles.tooling.preview)
    implementation(libs.androidx.watchface.complications.data.source.ktx)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.compose.material3)
    implementation("androidx.wear:wear-input:1.2.0")
    implementation(libs.compose.ui.tooling)
    implementation(libs.guava)
    implementation(libs.play.services.wearable)
    // 列表/按钮的线性图标（Add / Send / Delete / Settings 等）
    implementation(libs.material.icons.extended)
    // viewModel() 组合函数
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.tiles.renderer)
    debugImplementation(libs.androidx.tiles.tooling)
}
