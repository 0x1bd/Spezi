package spezi.backend

import spezi.domain.Type
import spezi.common.diagnostic.CompilerException

enum class QType(val code: String) {
    Word("w"),   // i32, bool
    Long("l"),   // i64, ptr
    Single("s"), // f32
    Double("d"), // f64
    Byte("b"),
    Void("");

    override fun toString() = code
}

fun mapTypeToQbe(t: Type): QType = when (t) {
    Type.I32, Type.Bool -> QType.Word
    Type.I64, Type.String -> QType.Long
    Type.F32 -> QType.Single
    Type.F64 -> QType.Double
    Type.Void -> QType.Void
    is Type.Struct -> QType.Long
    else -> throw CompilerException("Unsupported type for QBE: ${t.name}")
}