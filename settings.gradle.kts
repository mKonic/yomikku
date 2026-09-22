pluginManagement {
    resolutionStrategy {
        eachPlugin {
            // KMK --> the KMP library plugin ships in the same AGP artifact
            val regex = "com.android.(library|application|kotlin.multiplatform.library)".toRegex()
            // KMK <--
            if (regex matches requested.id.id) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kotlinx") {
            from(files("gradle/kotlinx.versions.toml"))
        }
        create("androidx") {
            from(files("gradle/androidx.versions.toml"))
        }
        create("compose") {
            from(files("gradle/compose.versions.toml"))
        }
        create("sylibs") {
            from(files("gradle/sy.versions.toml"))
        }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // KMK --> volition drives this app's debug build from adb by name (mKonic/volition), released
        // the same way: the AAR and its ivy descriptor as a tag's assets.
        exclusiveContent {
            forRepository {
                ivy("https://github.com/mKonic/volition/releases/download") {
                    patternLayout {
                        ivy("v[revision]/ivy-[revision].xml")
                        artifact("v[revision]/[artifact]-[revision].[ext]")
                    }
                    metadataSources { ivyDescriptor() }
                }
            }
            filter { includeModule("dev.mkonic", "volition") }
        }
        // KMK <--
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Yomikku"
include(":app")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":data")
include(":domain")
include(":i18n")
// KMK -->
include(":icons:material-symbols")
// KMK <--
// KMK -->
include(":i18n-kmk")
include(":flagkit")
// KMK <--
// SY -->
include(":i18n-sy")
// SY <--
include(":macrobenchmark")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
