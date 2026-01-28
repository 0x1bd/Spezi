package spezi.driver

import spezi.common.diagnostic.Diagnostic
import kotlin.time.Duration

sealed class CompilationResult {

    abstract val diagnostics: List<Diagnostic>

    data class Success(
        val compilationDuration: Duration,
        override val diagnostics: List<Diagnostic>
    ) : CompilationResult()

    data class Fail(
        override val diagnostics: List<Diagnostic>
    ) : CompilationResult()
}