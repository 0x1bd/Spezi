package spezi.driver

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.system
import spezi.backend.QBEBackend
import spezi.common.*
import spezi.common.diagnostic.CompilerException
import spezi.common.diagnostic.Level
import spezi.frontend.Parser
import spezi.frontend.semantic.SemanticAnalyzer
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.measureTime

object CompilerDriver {

    @OptIn(ExperimentalNativeApi::class)
    fun compile(options: CompilationOptions): CompilationResult {
        val ctx = Context(options)

        val time = measureTime {
            try {
                val mainFile = options.inputFiles.firstOrNull()
                if (mainFile == null) {
                    ctx.report(Level.ERROR, "No input file provided")
                    return CompilationResult.Fail(ctx.reporter.diagnostics)
                }

                if (!FileSystem.SYSTEM.exists(mainFile.toPath())) {
                    ctx.report(Level.ERROR, "Input file not found: $mainFile")
                    return CompilationResult.Fail(ctx.reporter.diagnostics)
                }

                ctx.currentSource = SourceFile.fromPath(mainFile)
                ctx.isModuleLoaded(mainFile)

                ctx.state = CompilationState.Parsing
                val parser = Parser(ctx)
                val ast = parser.parseProgram()

                if (ctx.reporter.hasErrors) {
                    return CompilationResult.Fail(ctx.reporter.diagnostics)
                }

                ctx.state = CompilationState.SemanticAnalysis
                val analyzer = SemanticAnalyzer(ctx, ast)
                analyzer.analyze()

                if (ctx.reporter.hasErrors) {
                    return CompilationResult.Fail(ctx.reporter.diagnostics)
                }

                ctx.state = CompilationState.Codegen
                val backend = QBEBackend(ctx)
                val ssaPath = "output.ssa"
                val asmPath = "output.s"

                val isWindows = Platform.osFamily == OsFamily.WINDOWS
                val exeExt = if (isWindows) ".exe" else ""
                val outName =
                    if (options.outputExe.endsWith(exeExt)) options.outputExe else "${options.outputExe}$exeExt"

                try {
                    backend.generate(ast)
                    backend.emitToFile(ssaPath)

                    if (!runQBE(ssaPath, asmPath)) {
                        throw CompilerException("QBE compilation failed. Ensure 'qbe' is in your PATH.")
                    }
                    if (!link(asmPath, outName, options)) {
                        throw CompilerException("Linking failed. Ensure 'clang' is in your PATH.")
                    }
                } finally {
                    backend.dispose()
                    if (!options.keepIr) {
                        FileSystem.SYSTEM.delete(ssaPath.toPath(), mustExist = false)
                        FileSystem.SYSTEM.delete(asmPath.toPath(), mustExist = false)
                    }
                }

            } catch (e: CompilerException) {
                ctx.report(Level.ERROR, e.message ?: "Unknown compiler error")
                return CompilationResult.Fail(ctx.reporter.diagnostics)
            } catch (e: Exception) {
                ctx.report(Level.ERROR, "Internal Compiler Error: ${e.message}")
                if (options.verbose) e.printStackTrace()
                return CompilationResult.Fail(ctx.reporter.diagnostics)
            }
        }

        return CompilationResult.Success(time, ctx.reporter.diagnostics)
    }

    private fun runQBE(ssaPath: String, asmPath: String): Boolean {
        val cmd = "qbe -o $asmPath $ssaPath"
        return system(cmd) == 0
    }

    private fun link(asmPath: String, exePath: String, options: CompilationOptions): Boolean {
        val libFlags = options.libraries.joinToString(" ") { "-l$it" }
        val defaults = "-lm"

        val cmd = buildString {
            append("clang ")
            append(asmPath)
            append(" -o $exePath ")
            if (options.optimizationLevel > 0) append("-O${options.optimizationLevel} ")
            append(libFlags)
            append(" ")
            append(defaults)
        }

        return system(cmd) == 0
    }
}