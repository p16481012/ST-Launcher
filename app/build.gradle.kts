import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build the notice index from the actual variant runtime graph. No second network request and
// no hardcoded imitation of a resolved version; debug-only dependencies stay out of release.
abstract class RuntimeLibraryIndex : DefaultTask() {
    @get:Input abstract val coordinates: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun writeIndex() {
        val output = outputDirectory.file("licenses/Android-runtime-libraries.txt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("이 APK의 런타임 의존성 (그룹:모듈:버전)")
            appendLine("AndroidX (Jetpack Compose, Material 3, Activity, Core, Lifecycle), Kotlin 및 kotlinx.coroutines: Apache License 2.0.")
            appendLine("공식 라이선스: https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt")
            appendLine("https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt")
            appendLine("https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt")
            appendLine("전체 Apache 2.0 본문은 이 앱에 포함된 Tink 및 ZXing 라이선스에서도 읽을 수 있어요.")
            appendLine("IBM Plex는 SIL Open Font License를 따릅니다. 폰트 원문과 개별 구성요소별 고지는 목록의 각 문서를 참고하세요.")
            appendLine()
            coordinates.get().forEach { appendLine(it) }
        })
    }
}

androidComponents.onVariants { variant ->
    val runtime = configurations.named("${variant.name}RuntimeClasspath")
    val index = tasks.register<RuntimeLibraryIndex>("index${variant.name.replaceFirstChar { it.uppercaseChar() }}LibraryNotices") {
        coordinates.set(runtime.map { config -> config.incoming.resolutionResult.allComponents.mapNotNull {
            (it.id as? ModuleComponentIdentifier)?.let { id -> "${id.group}:${id.module}:${id.version}" }
        }.distinct().sorted() })
        outputDirectory.set(layout.buildDirectory.dir("generated/notices/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(index, RuntimeLibraryIndex::outputDirectory)
}

android {
    namespace = "app.tavernbridge.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tavernbridge.stlauncher"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.crypto.tink:tink-android:1.23.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    testImplementation("junit:junit:4.13.2")
}
