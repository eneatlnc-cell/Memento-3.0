plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.ksp)
}

android {
  namespace = "com.myagent.app"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.myagent.app"
    minSdk = 31
    targetSdk = 36
    versionCode = 16
    versionName = "3.1.0"

    ndk {
      abiFilters += "arm64-v8a"  // llama.cpp 骁龙优化只支持 arm64-v8a
    }
  }

  // 只编译我们自己的 JNI wrapper（libllama_jni.so），
  // libllama.so 是预编译合体库（llama.rn 0.12.5），直接放 jniLibs
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      isMinifyEnabled = false
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources {
      excludes +=
        setOf(
          "/META-INF/{AL2.0,LGPL2.1}",
          "/META-INF/*.version",
          "/META-INF/LICENSE*.txt",
          "DebugProbesKt.bin",
          "kotlin-tooling-metadata.json",
        )
    }
    jniLibs {
      // libllama.so 是合体库（含 Hexagon NPU + OpenCL 后端静态链接），
      // strip 可能破坏 HTP 后端的特殊段；libllama_jni.so 体量小无需保护
      doNotStrip += listOf("**/libllama.so")
    }
  }

  lint {
    lintConfig = file("lint.xml")
    warningsAsErrors = true
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    allWarningsAsErrors.set(false)
  }
}

ktlint {
  android.set(true)
  ignoreFailures.set(false)
  filter {
    exclude("**/build/**")
  }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.webkit)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)

  debugImplementation(libs.androidx.compose.ui.tooling)

  // Material Components (XML theme + resources)
  implementation(libs.material)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.guava)
  implementation(libs.kotlinx.serialization.json)

  // llama.cpp — 通过 jniLibs 预编译 .so + JNI wrapper（无需 Maven 依赖）

  // ONNX Runtime — 端侧多模态推理（DreamLite + Kokoro-TTS）
  implementation(libs.onnxruntime.android)

  implementation(libs.androidx.security.crypto)

  // Markdown rendering
  implementation(libs.commonmark)
  implementation(libs.commonmark.ext.autolink)
  implementation(libs.commonmark.ext.gfm.strikethrough)
  implementation(libs.commonmark.ext.gfm.tables)
  implementation(libs.commonmark.ext.task.list.items)
  implementation(libs.coil.compose)

  // Navigation Compose
  implementation(libs.navigation.compose)

  // Room (SQLite memory layer)
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}