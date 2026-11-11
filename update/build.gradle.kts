import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.growth.update"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    // Android(D8)·서버 양쪽에서 읽히도록 바이트코드는 Java 11 로 낸다
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
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // Flow 를 공개 API 로 노출하므로 coroutines-core 는 api 다. Play 타입은 테스트용 진입점 하나를 빼고 드러내지 않는다
    implementation(libs.play.app.update)
    // Activity Result 런처(ActivityResultLauncher·IntentSenderRequest)를 받는 시작 경로가 공개 API 라 api 다
    api(libs.androidx.activity)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "update", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("growth-update")
        description.set("강제·권장 업데이트 판정(최소 버전 출처)과 Google Play In-App Updates 흐름을 제공하는 Android 라이브러리")
        inceptionYear.set("2026")
    }
}

// 라인 커버리지 90% 미만이면 check(=build)가 실패한다
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
