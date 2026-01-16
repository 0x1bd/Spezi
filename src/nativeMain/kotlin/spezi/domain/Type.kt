package spezi.domain

sealed interface Type {

    val name: kotlin.String

    data object Void : Type {

        override val name = "void"
    }

    data object Bool : Type {

        override val name = "bool"
    }

    data object String : Type {

        override val name = "string"
    }

    data object I32 : Type {

        override val name = "i32"
    }

    data object I64 : Type {

        override val name = "i64"
    }

    data object F32 : Type {

        override val name = "f32"
    }

    data object F64 : Type {

        override val name = "f64"
    }

    data class Struct(override val name: kotlin.String) : Type
    data object Unknown : Type {

        override val name = "unknown"
    }

    data object Error : Type {

        override val name = "<error>"
    }

    fun isNumber() = this is I32 || this is I64 || this is F32 || this is F64
    fun isInt() = this is I32 || this is I64
    fun isFloat() = this is F32 || this is F64
}