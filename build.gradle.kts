plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

group = "org.kvxd"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }

    linuxX64("native") {
        binaries {
            executable {
                entryPoint = "main"
            }
        }

        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-linker-options")
                    freeCompilerArgs.add("-L/usr/lib64 -lLLVM-21 -z muldefs")
                }
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(libs.clikt)
            implementation(libs.okio)
        }

        val nativeTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
