plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rdrgs.trens"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rdrgs.trens"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "4"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    androidResources {
        localeFilters += listOf("pt-rBR")
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {}
