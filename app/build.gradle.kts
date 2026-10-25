plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.volt.flowstudio"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.volt.flowstudio"
        minSdk = 26
        targetSdk = 34
        // CI 用 -PversionCode / -PversionName 注入；本地直接构建时用下面的默认值。
        // 之前这里是硬编码，workflow 里传的 -PversionName 根本没被读取 ——
        // 手填 1.0.0 打出来还是旧版本号，产物名和内容对不上。
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 28
        versionName = (findProperty("versionName") as String?) ?: "1.26"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-O2", "-fvisibility=hidden", "-mno-outline-atomics")
            }
        }
    }

    externalNativeBuild {
        cmake {
            // 用引擎自带的 CMakeLists，源码在仓库根目录 engine/ 下
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            // CI 上用环境变量注入；本地缺失时回落到 debug 签名
            val storePath = System.getenv("FLOW_KEYSTORE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("FLOW_KEYSTORE_PASS")
                keyAlias = System.getenv("FLOW_KEY_ALIAS") ?: "flowstudio"
                keyPassword = System.getenv("FLOW_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("FLOW_KEYSTORE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        // so 不打进压缩块，安装时直接 mmap，启动更快
        jniLibs.useLegacyPackaging = false
    }

    // so 完全由 CMake 从 engine/ 源码编出来，没有预编译产物参与，
    // 因此不存在同名文件互相覆盖的问题。

}

dependencies {
    // 刻意零第三方依赖：整个音频引擎是自写的 C++，UI 全部自绘。
    // 这样 APK 只有一个 so + 一份 dex，启动最快，也没有供应链风险。
    implementation(kotlin("stdlib"))
}

