package spezi.common.diagnostic

import spezi.domain.Token

data class Diagnostic(
    val level: Level,
    val message: String,
    val loc: Token? = null
)