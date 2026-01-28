package spezi.backend

class QbeEmitter {

    private val sb = StringBuilder()
    private var tmpCount = 0
    private var labelCount = 0

    fun clear() {
        sb.clear()
        tmpCount = 0
        labelCount = 0
    }

    fun build(): String = sb.toString()

    fun newTmp(): String = "%.t${tmpCount++}"
    fun newLabel(): String = "@.L${labelCount++}"

    fun emitType(name: String, fields: List<QType>) {
        val desc = fields.joinToString(", ") { it.code }
        raw("type :$name = { $desc }")
    }

    fun emitData(label: String, content: String) {
        val escaped = content.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        raw("data $label = { b \"$escaped\", b 0 }")
    }

    fun startFunction(export: Boolean, name: String, args: List<Pair<String, QType>>, ret: QType) {
        val prefix = if (export) "export " else ""
        val retStr = if (ret == QType.Void) "" else ret.code

        val argStr = args.joinToString(", ") { (n, t) -> "${t.code} %.$n" }

        raw("\n$prefix function $retStr \$$name($argStr) {")
        emitLabel("entry")
    }

    fun endFunction() {
        raw("}")
    }

    fun emitLabel(name: String) {
        val clean = name.removePrefix("@")
        raw("@$clean")
    }

    /**
     * Assigns result of operation to a new temporary.
     * returns: %tmp
     */
    fun assign(type: QType, op: String, vararg args: String): String {
        val res = newTmp()
        raw("\t$res =$type $op ${args.joinToString(", ")}")
        return res
    }

    /**
     * Instruction with no return value (or void call).
     */
    fun instr(op: String, vararg args: String) {
        raw("\t$op ${args.joinToString(", ")}")
    }

    fun call(name: String, args: List<String>, retType: QType): String {
        val argStr = args.joinToString(", ")
        return if (retType == QType.Void) {
            instr("call \$$name($argStr)")
            ""
        } else {
            val res = newTmp()
            raw("\t$res =$retType call \$$name($argStr)")
            res
        }
    }

    fun allocNamed(name: String, size: Int) {
        val align = if (size >= 8) 8 else 4
        raw("\t%$name =l alloc$align $size")
    }

    fun alloc(size: Int): String {
        val align = if (size >= 8) 8 else 4
        val ptr = newTmp()
        raw("\t$ptr =l alloc$align $size")
        return ptr
    }

    fun store(type: QType, value: String, ptr: String) {
        raw("\tstore$type $value, $ptr")
    }

    fun load(type: QType, ptr: String): String {
        val res = newTmp()
        raw("\t$res =$type load$type $ptr")
        return res
    }

    fun jmp(label: String) = instr("jmp", label)
    fun jnz(cond: String, lTrue: String, lFalse: String) = instr("jnz", cond, lTrue, lFalse)
    fun ret(value: String? = null) {
        if (value != null) instr("ret", value) else instr("ret")
    }

    private fun raw(s: String) {
        sb.append(s).append("\n")
    }
}