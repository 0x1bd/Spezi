package spezi.backend

import spezi.domain.*

class QbeContext {

    data class StructLayout(val size: Int, val offsets: Map<String, Int>)

    val structLayouts = mutableMapOf<String, StructLayout>()
    val strings = mutableMapOf<String, String>()

    val externs = mutableSetOf<String>()
    val functions = mutableSetOf<String>() // mangled names

    private var strCount = 0

    fun analyze(p: Program) {
        clear()

        p.elements.filterIsInstance<StructDef>().forEach { s ->
            var offset = 0
            val offsets = mutableMapOf<String, Int>()
            s.fields.forEach { (name, type) ->
                offsets[name] = offset
                offset += getTypeSize(type)
            }
            structLayouts[s.name] = StructLayout(offset, offsets)
        }

        p.elements.filterIsInstance<ExternFnDef>().forEach {
            externs.add(it.name)
        }

        p.elements.filterIsInstance<FnDef>().forEach { fn ->
            val mangled = QbeNameMangler.mangle(
                fn.module, fn.name, fn.args.map { it.second }, fn.extensionOf, false
            )
            functions.add(mangled)
        }
    }

    private fun clear() {
        structLayouts.clear()
        strings.clear()
        externs.clear()
        functions.clear()
        strCount = 0
    }

    fun getTypeSize(t: Type): Int = when (t) {
        Type.I32, Type.Bool, Type.F32 -> 4
        Type.I64, Type.F64, Type.String -> 8
        is Type.Struct -> structLayouts[t.name]?.size ?: 8
        else -> 8
    }

    fun getStringLabel(value: String): String {
        return strings.getOrPut(value) { "$.str${strCount++}" }
    }

    fun resolveFunction(name: String, args: List<Type>): String {
        val simpleMangle = QbeNameMangler.mangle("", name, args, null, false)
        if (functions.contains(simpleMangle)) return simpleMangle

        if (args.isNotEmpty() && args[0] is Type.Struct) {
            val extMangle = QbeNameMangler.mangle("", name, args.drop(1), args[0], false)
            if (functions.contains(extMangle)) return extMangle
        }

        if (externs.contains(name)) return name

        val flatName = name.replace('.', '_')
        val flatMangle = QbeNameMangler.mangle("", flatName, args, null, false)
        if (functions.contains(flatMangle)) return flatMangle

        return simpleMangle
    }
}