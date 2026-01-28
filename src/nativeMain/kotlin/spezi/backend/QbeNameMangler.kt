package spezi.backend

import spezi.domain.Type

object QbeNameMangler {
    
    fun mangle(module: String, name: String, argTypes: List<Type>, extensionOf: Type?, isExtern: Boolean): String {
        if (isExtern || name == "main") return name
        
        val modPrefix = if (module.isNotEmpty()) "${module.replace('.', '_')}_" else ""
        val classPrefix = if (extensionOf != null) "${extensionOf.name}_" else ""
        
        val argSuffix = if (argTypes.isEmpty()) ""
                        else "_" + argTypes.joinToString("_") { it.name }
        
        return "$modPrefix$classPrefix$name$argSuffix"
    }
}