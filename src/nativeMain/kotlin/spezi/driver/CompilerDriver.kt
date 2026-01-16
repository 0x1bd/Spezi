package spezi.driver

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.*
import spezi.backend.LLVMBackend
import spezi.common.*
import spezi.frontend.Parser
import spezi.frontend.semantic.SemanticAnalyzer

object CompilerDriver {

    fun compile(options: CompilationOptions): Boolean {
        val ctx = Context(options)

        try {
            if (options.verbose) ctx.reporter.info("Parsing...")

            val mainFile = options.inputFiles.firstOrNull() ?: return false

            if (!FileSystem.SYSTEM.exists(mainFile.toPath())) {
                printError("Input file not found: $mainFile")
                return false
            }

            ctx.currentSource = SourceFile.fromPath(mainFile)
            ctx.isModuleLoaded(mainFile)

            val parser = Parser(ctx)
            val ast = parser.parseProgram()

            if (options.verbose) ctx.reporter.info("Checking...")
            val analyzer = SemanticAnalyzer(ctx, ast)
            analyzer.analyze()

            if (ctx.reporter.hasErrors) return false

            if (options.verbose) ctx.reporter.info("Generating Code...")
            val backend = LLVMBackend(ctx)

            try {
                backend.generate(ast)

                val irPath = "output.ll"
                backend.emitToFile(irPath)

                val success = link(irPath, options)

                if (!options.keepIr) {
                    FileSystem.SYSTEM.delete(irPath.toPath())
                }

                return success

            } finally {
                backend.dispose()
            }

        } catch (e: Exception) {
            printError("Compiler Error: ${e.message}")
            if (options.verbose) e.printStackTrace()
            return false
        }
    }

    private fun link(irPath: String, options: CompilationOptions): Boolean {
        val libFlags = options.libraries.joinToString(" ") { "-l$it" }
        val defaults = if (options.libraries.isEmpty()) "-lc -lm" else ""

        val cmd = "clang $irPath -o ${options.outputExe} -O${options.optimizationLevel} $libFlags $defaults"

        if (options.verbose) println("Linking: $cmd")

        val res = system(cmd)
        return res == 0
    }

    private fun printError(msg: String) {
        fprintf(stderr, "%s\n", msg)
    }
}