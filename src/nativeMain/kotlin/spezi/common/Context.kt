package spezi.common

import com.github.ajalt.mordant.terminal.Terminal
import okio.FileSystem
import okio.Path.Companion.toPath
import spezi.common.diagnostic.DiagnosticReporter

class Context(val options: CompilationOptions) {

    val terminal = Terminal()
    val reporter = DiagnosticReporter(terminal, this)

    var currentSource: SourceFile = SourceFile("<unknown>", "")

    var source: SourceFile
        get() = currentSource
        set(value) {
            currentSource = value
        }

    private val loadedModules = mutableSetOf<String>()

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