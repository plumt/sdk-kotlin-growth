import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.growth.update.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    explicitApi()
}

dependencies {
    // 공개 API 가 UpdateAction·UpdateCheckResult 를 드러내므로 api 다
    api(project(":update"))

    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(bom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "update-compose", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("growth-update-compose")
        description.set("업데이트 안내 다이얼로그와 강제 업데이트 차단 화면을 Compose 로 제공하는 Android 라이브러리(교체 가능)")
        inceptionYear.set("2026")
    }
}

// 라인 커버리지 90% 미만이면 check(=build)가 실패한다
kover {
    reports {
        filters {
            excludes {
                // 화면을 그리는 @Composable 은 유닛 테스트 대상이 아니다. 화면 표시 판단은 순수 함수(UpdateUiModel)로 빼서 테스트한다.
                annotatedBy("androidx.compose.runtime.Composable")
                // Compose 컴파일러가 만드는 람다 보관 클래스
                classes("*ComposableSingletons*")
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
