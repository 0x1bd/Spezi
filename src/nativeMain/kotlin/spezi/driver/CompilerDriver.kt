package spezi.driver

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.system
import spezi.backend.LLVMBackend
import spezi.common.*
import spezi.common.diagnostic.CompilerException
import spezi.common.diagnostic.Level
import spezi.common.diagnostic.report
import spezi.frontend.Parser
import spezi.frontend.semantic.SemanticAnalyzer
import kotlin.time.measureTime

object CompilerDriver {

    fun compile(options: CompilationOptions): CompilationResult {
        val ctx = Context(options)

        try {
            ctx.reporter.state = CompilationState.Parsing

            val time = measureTime {
                val mainFile = options.inputFiles.firstOrNull()
                    ?: return CompilationResult.Fail

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

                if (ctx.reporter.hasErrors) {
                    return CompilationResult.Fail
                }

                ctx.reporter.state = CompilationState.Codegen

                val backend = LLVMBackend(ctx)

                val irPath = "output.ll"
                val objPath = "output.o"

                try {
                    backend.generate(ast)
                    backend.emitToFile(irPath)

                    if (!runLLC(irPath, objPath)) {
                        throw CompilerException("llc failed")
                    }

                    if (!link(objPath, options)) {
                        throw CompilerException("Linking failed")
                    }

                } finally {
                    backend.dispose()

                    if (!options.keepIr) {
                        FileSystem.SYSTEM.delete(irPath.toPath(), mustExist = false)
                        FileSystem.SYSTEM.delete(objPath.toPath(), mustExist = false)
                    }
                }
            }

            return CompilationResult.Success(time)

        } catch (e: Exception) {
            ctx.report(Level.ERROR, "Compiler error: ${e.message}")

            if (options.verbose) {
                e.printStackTrace()
            }
            return CompilationResult.Fail
        }
    }

    private fun runLLC(irPath: String, objPath: String): Boolean {
        val cmd = "llc $irPath -filetype=obj -o $objPath"
        return system(cmd) == 0
    }

    private fun link(objPath: String, options: CompilationOptions): Boolean {
        val libFlags = options.libraries.joinToString(" ") { "-l$it" }
        val defaults = if (options.libraries.isEmpty()) "-lc -lm" else ""

        val cmd = buildString {
            append("clang ")
            append("-fuse-ld=lld ")
            append(objPath)
            append(" -o ${options.outputExe} ")
            append("-O${options.optimizationLevel} ")
            append(libFlags)
            append(" ")
            append(defaults)
        }

        return system(cmd) == 0
    }
}
