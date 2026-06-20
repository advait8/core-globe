plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "11"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

android {
    namespace = "dev.advaitm.coreglobe"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

mavenPublishing {
    coordinates("io.github.advait8", "core-globe", "0.1.0")

    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name.set("core-globe")
        description.set("Kotlin Multiplatform library for rendering an interactive 3D globe in a WebView using Three.js")
        inceptionYear.set("2025")
        url.set("https://github.com/advait8/core-globe")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("advait8")
                name.set("Advait Mahashabde")
                email.set("advait.8.m@gmail.com")
                url.set("https://github.com/advait8/")
            }
        }
        scm {
            url.set("https://github.com/advait8/core-globe/")
            connection.set("scm:git:git://github.com/advait8/core-globe.git")
            developerConnection.set("scm:git:ssh://git@github.com/advait8/core-globe.git")
        }
    }
}
