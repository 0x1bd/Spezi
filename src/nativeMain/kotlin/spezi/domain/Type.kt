package spezi.domain

sealed interface Type {

    val name: kotlin.String

    data object Void : Type {

        override val name = "void"
    }

    data object I32 : Type {

        override val name = "i32"
    }

    data object Bool : Type {

        override val name = "bool"
    }

    data object String : Type {

        override val name = "string"
    }

    data class Struct(override val name: kotlin.String) : Type
    data object Unknown : Type {

        override val name = "unknown"
    }

    data object Error : Type { override val name = "<error>" }
}