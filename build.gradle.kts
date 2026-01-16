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
        compilations["main"].cinterops {
            val llvm by creating {
                defFile(project.file("src/nativeInterop/cinterop/llvm.def"))
            }
        }

        binaries {
            executable {
                linkerOpts(
                    "-L/usr/lib64",
                    "-lLLVM-21",
                    "-z",
                    "muldefs"
                )

                entryPoint = "main"
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
