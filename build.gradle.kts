plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

group = "me.user"
version = "1.0-SNAPSHOT"

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
                linkerOpts("-L/usr/lib64", "-lLLVM-21")

                entryPoint = "main"
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation("com.github.ajalt.clikt:clikt:5.0.3")
            implementation("com.github.ajalt.mordant:mordant:3.0.2")
            implementation("com.squareup.okio:okio:3.16.4")
        }
    }
}
