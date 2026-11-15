plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.siye.littletoolbox"
    compileSdk = libs.versions.android.compile.get().toInt()

    defaultConfig {
        applicationId = "io.github.siye.littletoolbox"
        minSdk = libs.versions.android.min.get().toInt()
        targetSdk = libs.versions.android.compile.get().toInt()
        versionCode = 2
        versionName = "0.2.0-dev"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:tool-api"))
    implementation(project(":tools:audio"))
    implementation(project(":tools:image"))
    implementation(project(":tools:quicknote"))
    implementation(project(":tools:textstats"))
    implementation(project(":tools:trail"))
    implementation(project(":tools:wifi"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
}
