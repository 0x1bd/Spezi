package spezi.common

import com.github.ajalt.mordant.terminal.Terminal

data class CompilationOptions(
    val inputFile: String,
    val outputExe: String,
    val emitIr: Boolean,
    val verbose: Boolean,
    val optimizationLevel: Int,
    val libraries: List<String>
)

class Context(val options: CompilationOptions) {

    val terminal = Terminal()
    val reporter = DiagnosticReporter(terminal)
    lateinit var source: SourceFile

    fun loadSource() {
        source = SourceFile.fromPath(options.inputFile)
    }
}