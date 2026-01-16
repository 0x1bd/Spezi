package spezi.frontend.semantic

import spezi.domain.FnDef
import spezi.domain.Type

class AnalysisContext {

    private val scopes = ArrayDeque<MutableMap<String, Type>>()
    var currentFunction: FnDef? = null
    var isInsideLoop: Boolean = false

    fun enterScope() = scopes.addFirst(mutableMapOf())
    fun exitScope() = scopes.removeFirst()

    fun define(name: String, type: Type) {
        scopes.first()[name] = type
    }

    fun lookup(name: String): Type? = scopes.firstNotNullOfOrNull { it[name] }

    fun isDefinedInCurrentScope(name: String) = scopes.first().containsKey(name)
}