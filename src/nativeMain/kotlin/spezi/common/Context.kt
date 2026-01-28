package spezi.common

import okio.FileSystem
import okio.Path.Companion.toPath
import spezi.common.diagnostic.DiagnosticReporter
import spezi.common.diagnostic.Level
import spezi.domain.Token

class Context(val options: CompilationOptions) {

    val reporter = DiagnosticReporter(this)
    var state: CompilationState = CompilationState.Reading
    var currentSource: SourceFile = SourceFile("<unknown>", "")
    private val loadedModules = mutableSetOf<String>()

    fun report(level: Level, msg: String, loc: Token? = null) {
        reporter.report(level, msg, loc)
    }

    fun resolveImport(importName: String): String? {
        val relativePath = importName.replace('.', '/') + ".spz"
        val local = relativePath.toPath()
        if (FileSystem.SYSTEM.exists(local)) return local.toString()

        for (path in options.includePaths) {
            val candidate = path.toPath() / relativePath
            if (FileSystem.SYSTEM.exists(candidate)) return candidate.toString()
        }
        return null
    }

    fun isModuleLoaded(path: String): Boolean {
        val norm = path.toPath().normalized().toString()
        if (loadedModules.contains(norm)) return true
        loadedModules.add(norm)
        return false
    }
}