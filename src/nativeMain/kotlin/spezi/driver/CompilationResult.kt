package spezi.driver

import kotlin.time.Duration

abstract class CompilationResult {

    class Success(
        val compilationDuration: Duration,
    ) : CompilationResult()

    object Fail : CompilationResult()

}