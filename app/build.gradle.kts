plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.taxipro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taxipro"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.0"
        resValue("string", "admob_app_id", providers.gradleProperty("ADMOB_APP_ID")
            .getOrElse("ca-app-pub-6621304807079356~7365210074"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.maps.android:maps-compose:6.1.0")

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.location)
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.android.billingclient:billing-ktx:7.0.0")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-config")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
