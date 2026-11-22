import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

// Release signing stays outside the repository: local.properties (git-ignored) may set
// retra.signing=<path to a properties file with storeFile, storePassword, keyAlias, keyPassword>.
// Without it, release builds are unsigned as before.
val retraSigning = Properties().apply {
    val local = rootProject.file("local.properties")
    val path = if (local.isFile) Properties().apply { local.inputStream().use { load(it) } }.getProperty("retra.signing") else null
    val file = path?.let { File(it) }
    if (file != null && file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "com.retra.emulator"
    // NDK r28+ produces 16 KB page-size compatible native libraries by default.
    ndkVersion = "28.0.13004108"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.retra.emulator"
        minSdk = 26
        targetSdk = 37
        versionCode = 454
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        if (retraSigning.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(retraSigning.getProperty("storeFile"))
                storePassword = retraSigning.getProperty("storePassword")
                keyAlias = retraSigning.getProperty("keyAlias")
                keyPassword = retraSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
}