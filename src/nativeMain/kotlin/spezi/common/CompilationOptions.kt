package spezi.common

data class CompilationOptions(
    val inputFiles: List<String>,
    val outputExe: String,
    val keepIr: Boolean,
    val verbose: Boolean,
    val optimizationLevel: Int,
    val libraries: List<String>,
    val includePaths: List<String>
)