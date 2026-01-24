package spezi.driver

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.*
import spezi.backend.LLVMBackend
import spezi.common.*
import spezi.common.diagnostic.CompilerException
import spezi.frontend.Parser
import spezi.frontend.semantic.SemanticAnalyzer
import kotlin.time.measureTime

object CompilerDriver {

    fun compile(options: CompilationOptions): CompilationResult {
        val ctx = Context(options)

        try {
            ctx.reporter.state = CompilationState.Parsing

            val time = measureTime {
                val mainFile = options.inputFiles.firstOrNull() ?: return CompilationResult.Fail

                if (!FileSystem.SYSTEM.exists(mainFile.toPath())) {
                    ctx.reporter.error("Input file not found: $mainFile")
                    return CompilationResult.Fail
                }

                ctx.currentSource = SourceFile.fromPath(mainFile)
                ctx.isModuleLoaded(mainFile)

                val parser = Parser(ctx)
                val ast = parser.parseProgram()

                ctx.reporter.state = CompilationState.SemanticAnalysis
                val analyzer = SemanticAnalyzer(ctx, ast)
                analyzer.analyze()

                if (ctx.reporter.hasErrors) return CompilationResult.Fail

                ctx.reporter.state = CompilationState.Codegen
                val backend = LLVMBackend(ctx)

                try {
                    backend.generate(ast)

                    val irPath = "output.ll"
                    backend.emitToFile(irPath)

                    val success = link(ctx, irPath, options)

                    if (!success) {
                        throw CompilerException("Linking failed")
                    }

                    if (!options.keepIr) {
                        FileSystem.SYSTEM.delete(irPath.toPath())
                    }

                } finally {
                    backend.dispose()
                }
            }

            return CompilationResult.Success(time)
        } catch (e: Exception) {
            ctx.reporter.error("Compiler Error: ${e.message}")
            if (options.verbose) e.printStackTrace()

            return CompilationResult.Fail
        }
    }

    private fun link(ctx: Context, irPath: String, options: CompilationOptions): Boolean {
        val libFlags = options.libraries.joinToString(" ") { "-l$it" }
        val defaults = if (options.libraries.isEmpty()) "-lc -lm" else ""

        val cmd = "clang $irPath -o ${options.outputExe} -O${options.optimizationLevel} $libFlags $defaults"

        ctx.reporter.state = CompilationState.Linking

        val res = system(cmd)
        return res == 0
    }
}