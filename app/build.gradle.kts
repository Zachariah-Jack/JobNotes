plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pelicankb.jobnotes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pelicankb.jobnotes"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug { isMinifyEnabled = false }
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

}

dependencies {
    // AndroidX core KTX
    implementation(libs.androidx.core.ktx)

    // AppCompat
    implementation(libs.androidx.appcompat)

    // Material Components
    implementation(libs.material)

    // Activity KTX
    implementation(libs.androidx.activity)

    // ConstraintLayout
    implementation(libs.androidx.constraintlayout)

    // Color picker (already in your catalog)
    implementation(libs.skydoves.colorpickerview)

    // (Optional) CameraX etc. — add here using catalog aliases if you’ve defined them
    // implementation(libs.androidx.camera.view)
    // implementation(libs.androidx.camera.lifecycle)
}
