package spezi.backend

import llvm.LLVMTypeRef
import llvm.LLVMValueRef

class ScopedSymbolTable {

    data class VarInfo(val alloca: LLVMValueRef, val type: LLVMTypeRef, val isMut: Boolean)

    private val scopes = ArrayDeque<MutableMap<String, VarInfo>>()

    fun enterScope() = scopes.addFirst(mutableMapOf())
    fun exitScope() = scopes.removeFirst()

    fun define(name: String, alloca: LLVMValueRef, type: LLVMTypeRef, isMut: Boolean) {
        scopes.first()[name] = VarInfo(alloca, type, isMut)
    }

    fun lookup(name: String): VarInfo? {
        return scopes.firstNotNullOfOrNull { it[name] }
    }
}