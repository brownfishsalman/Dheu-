// Kotlin compilation is built into AGP 9 (no org.jetbrains.kotlin.android plugin);
// the Compose and serialization compiler plugins are still declared here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
