package spezi.common

import com.github.ajalt.mordant.terminal.Terminal
import okio.FileSystem
import okio.Path.Companion.toPath

data class CompilationOptions(
    val inputFiles: List<String>,
    val outputExe: String,
    val keepIr: Boolean,
    val verbose: Boolean,
    val optimizationLevel: Int,
    val libraries: List<String>,
    val includePaths: List<String>
)

class Context(val options: CompilationOptions) {
    val terminal = Terminal()
    val reporter = DiagnosticReporter(terminal)

    var currentSource: SourceFile = SourceFile("<unknown>", "")

    var source: SourceFile
        get() = currentSource
        set(value) { currentSource = value }

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