package spezi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import spezi.common.CompilationOptions
import spezi.common.diagnostic.Diagnostic
import spezi.common.diagnostic.Level
import spezi.driver.CompilationResult
import spezi.driver.CompilerDriver.compile
import kotlin.system.exitProcess

object SpeziCommand : CliktCommand(name = "spezi") {

    val input by argument().help("Main source file")
    val output by option("-o", "--output").default("out.out")
        .help("Output file")
    val emitIr by option("--emit-ir").flag(default = false)
        .help("Keep IR files")
    val verbose by option("-v", "--verbose").flag()
        .help("Enable verbose logging")
    val optLevel by option("-O").int().default(0)
        .help("Clang optimization level")
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

        val result = compile(opts)
        val term = Terminal()

        if (result.diagnostics.isNotEmpty()) {
            printDiagnostics(term, result.diagnostics)
        }

        when (result) {
            is CompilationResult.Fail -> {
                term.println(red(bold("Compilation failed with ${result.diagnostics.size} errors.")))
                exitProcess(1)
            }
            is CompilationResult.Success -> {
                term.println(green(bold("Compilation succeeded in ${result.compilationDuration.inWholeMilliseconds}ms")))
            }
        }
    }

    private fun printDiagnostics(term: Terminal, diagnostics: List<Diagnostic>) {
        diagnostics.forEach { diag ->
            val color = when (diag.level) {
                Level.ERROR -> red
                Level.WARN -> yellow
                Level.INFO -> blue
            }

            term.println()
            term.println("${color(bold(diag.level.name))}: ${diag.message}")

            if (diag.loc != null) {
                val loc = diag.loc
                val source = loc.source
                val lineIdx = loc.line - 1
                val rawLine = source.lines.getOrNull(lineIdx) ?: ""
                val lineNoStr = loc.line.toString()

                term.println("  ${gray("-->")} ${source.path}:${loc.line}:${loc.col}")
                term.println("  ${gray(" | ")}")
                term.println("  ${gray("$lineNoStr | ")} ${rawLine.replace("\t", "    ")}")

                val padding = " ".repeat(loc.col - 1 + lineNoStr.length + 4)
                val pointer = "^".repeat(loc.length.coerceAtLeast(1))
                term.println("  $padding${color(pointer)}")
            }
        }
        term.println()
    }
}