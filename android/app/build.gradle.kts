import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Supabase settings live in android/local.properties (not committed):
//   dheu.supabaseUrl=https://<project>.supabase.co
//   dheu.supabaseKey=<publishable key>
//   dheu.siteUrl=https://dheu-beige.vercel.app
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
fun setting(key: String): String = local.getProperty(key) ?: ""

android {
    namespace = "com.salmansaadiq.dheu"
    // The current AndroidX releases (navigation 2.10, lifecycle 2.11...) require compiling against API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.salmansaadiq.dheu"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", "\"${setting("dheu.supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${setting("dheu.supabaseKey")}\"")
        buildConfigField("String", "SITE_URL", "\"${setting("dheu.siteUrl").ifEmpty { "https://dheu-beige.vercel.app" }}\"")
    }

    buildTypes {
        release {
            // R8 strips unused code and resources from the libraries.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    compilerOptions {
        // Top app bars, bottom sheets and pull-to-refresh are still marked experimental in Material 3.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
