plugins {
    id("mihon.library")
}

android {
    namespace = "tachiyomi.source.local"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    api(projects.i18n)
    // SY -->
    api(projects.i18nSy)
    // SY <--

    implementation(libs.unifile)

    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)

    // Move ChapterRecognition to separate module?
    implementation(projects.domain)

    implementation(kotlinx.bundles.serialization)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
