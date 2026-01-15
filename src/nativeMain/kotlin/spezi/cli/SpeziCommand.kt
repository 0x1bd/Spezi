package spezi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.system
import spezi.backend.LLVMBackend
import spezi.common.CompilationOptions
import spezi.common.Context
import spezi.frontend.Parser
import spezi.frontend.TypeChecker
import kotlin.system.exitProcess

class SpeziCommand : CliktCommand(name = "spezic") {

    val inputPath by argument("input", help = "Input source file (.spz)")

    private val output by option("-o", "--output", help = "Output binary name")
        .default("out")

    private val emitIr by option("--emit-ir", help = "Emit LLVM IR to file")
        .flag()

    private val verbose by option("-v", "--verbose", help = "Enable verbose logging")
        .flag()

    private val optLevel by option("-O", help = "Optimization level (0–3)")
        .int()
        .default(0)

    private val libs by option("-l", help = "Link libraries (e.g. -l c -l m)")
        .multiple()

    override fun run() {
        val options = CompilationOptions(
            inputFile = inputPath,
            outputExe = output,
            emitIr = emitIr,
            verbose = verbose,
            optimizationLevel = optLevel,
            libraries = libs
        )

        val ctx = Context(options)

        try {
            ctx.loadSource()

            val parser = Parser(ctx)
            val ast = parser.parseProgram()

            val checker = TypeChecker(ctx, ast)
            checker.check()

            if (ctx.reporter.hasErrors) {
                echo("Compilation failed due to static analysis errors.")
                exitProcess(1)
            }

            val backend = LLVMBackend(ctx)
            backend.generate(ast)

            val irFile = "output.ll".toPath()
            backend.emitToFile(irFile.toString())

            linkBinary(irFile, options)

        } catch (e: Exception) {
            echo("Internal Compiler Error: ${e.message}", err = true)
            if (verbose) e.printStackTrace()
            exitProcess(1)
        }
    }

    private fun linkBinary(irPath: Path, opts: CompilationOptions) {
        val linkFlags = opts.libraries.joinToString(" ") { "-l$it" }
        val defaults = if (opts.libraries.isEmpty()) "-lc -lm" else ""
        val cmd = "clang $irPath -o ${opts.outputExe} -O${opts.optimizationLevel} $linkFlags $defaults"

        if (opts.verbose) echo("Linking: $cmd")

        val result = system(cmd)

        if (result != 0) {
            echo("Linker Error (exit code $result)", err = true)
            exitProcess(1)
        } else {
            echo("Build successful: ${opts.outputExe}")
        }
    }
}