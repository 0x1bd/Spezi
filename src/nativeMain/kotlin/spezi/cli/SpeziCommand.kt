package spezi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import spezi.common.CompilationOptions
import spezi.driver.CompilerDriver
import kotlin.system.exitProcess

class SpeziCommand : CliktCommand(name = "spezi") {

    val input by argument().help("Main source file")
    val output by option("-o", "--output").default("a.out")
    val emitIr by option("--emit-ir").flag(default = false)
    val verbose by option("-v", "--verbose").flag()
    val optLevel by option("-O").int().default(0)
    val libs by option("-l").multiple()
    val includes by option("-I").multiple()

    override fun run() {
        val opts = CompilationOptions(
            inputFiles = listOf(input),
            outputExe = output,
            keepIr = emitIr,
            verbose = verbose,
            optimizationLevel = optLevel,
            libraries = libs,
            includePaths = includes + "std"
        )

        if (!CompilerDriver.compile(opts)) {
            exitProcess(1)
        }
    }
}