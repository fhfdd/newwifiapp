// 模块级 app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.indoornavblind"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.indoornavblind"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // 移除kapt配置块（Java项目用annotationProcessor，无需kapt）
}

dependencies {
    // 基础依赖
    implementation(libs.androidx.core.ktx) // 可选：若用Java可替换为androidx.core:core
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // 测试依赖
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Retrofit（网络请求）
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // Room数据库（Java项目用annotationProcessor替代kapt）
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler) // 替换kapt为annotationProcessor

    // Gson
    implementation(libs.gson)
}