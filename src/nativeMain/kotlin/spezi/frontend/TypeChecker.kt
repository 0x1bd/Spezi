package spezi.frontend

import spezi.common.CompilerException
import spezi.common.Context
import spezi.domain.*

class TypeChecker(private val ctx: Context, private val prog: Program) {
    private val scopes = ArrayDeque<MutableMap<String, Type>>()
    private val structs = mutableMapOf<String, StructDef>()
    private val functions = mutableListOf<FnDef>()
    private val externs = mutableMapOf<String, ExternFnDef>()
    private var hasError = false

    private fun error(msg: String, loc: Token) {
        ctx.reporter.error(msg, loc, ctx.source)
        hasError = true
    }

    fun check() {
        if (ctx.options.verbose) ctx.reporter.info("Starting Semantic Analysis...")
        prog.elements.filterIsInstance<StructDef>().forEach { structs[it.name] = it }
        prog.elements.filterIsInstance<FnDef>().forEach { functions.add(it) }
        prog.elements.filterIsInstance<ExternFnDef>().forEach { externs[it.name] = it }
        prog.elements.filterIsInstance<FnDef>().forEach { checkFn(it) }

        if (hasError) throw CompilerException("Analysis failed")
    }

    private fun checkFn(fn: FnDef) {
        scopes.addFirst(mutableMapOf())
        if (fn.extensionOf != null) {
            if (fn.extensionOf is Type.Struct && !structs.containsKey(fn.extensionOf.name))
                error("Unknown extension struct", fn.loc)
            scopes.first()["self"] = fn.extensionOf
        }
        fn.args.forEach { scopes.first()[it.first] = it.second }
        checkBlock(fn.body)
        scopes.removeFirst()
    }

    private fun checkBlock(b: Block) {
        b.stmts.forEach { s ->
            when (s) {
                is VarDecl -> {
                    val iType = if (s.init != null) inferType(s.init) else Type.Void

                    if (iType == Type.Error) {
                        scopes.first()[s.name] = Type.Error
                    } else {
                        val type = s.type ?: iType
                        if (type == Type.Unknown || type == Type.Void) error("Cannot infer type for '${s.name}'", s.loc)
                        if (s.init != null && s.type != null && type != iType) error("Type mismatch", s.loc)
                        scopes.first()[s.name] = type
                        b.declaredVars.add(s)
                    }
                }
                is Assign -> {
                    if (!lookup(s.name)) error("Undefined variable '${s.name}'", s.loc)
                    else {
                        val varType = scopes.first { it.containsKey(s.name) }[s.name]!!
                        val valType = inferType(s.value)
                        if (varType != Type.Error && valType != Type.Error && varType != valType)
                            error("Assign type mismatch", s.loc)
                    }
                }
                is IfStmt -> { inferType(s.cond); checkBlock(s.thenBlock); s.elseBlock?.let { checkBlock(it) } }
                is ReturnStmt -> if (s.value != null) { inferType(s.value) }
                is Expr -> inferType(s)
                else -> {}
            }
        }
    }

    private fun inferType(e: Expr): Type {
        val t = when (e) {
            is LiteralInt -> Type.I32
            is LiteralBool -> Type.Bool
            is LiteralString -> Type.String
            is VarRef -> {
                val vt = scopes.firstOrNull { it.containsKey(e.name) }?.get(e.name)
                if (vt == null) { error("Undefined variable '${e.name}'", e.loc); Type.Error } else vt
            }
            is BinOp -> {
                val l = inferType(e.left); val r = inferType(e.right)
                if (l == Type.Error || r == Type.Error) Type.Error
                else if (l != r) { error("Binary op mismatch: ${l.name} vs ${r.name}", e.loc); Type.Error }
                else if (e.op == TokenType.EQEQ || e.op == TokenType.NEQ) Type.Bool else l
            }
            is Call -> resolveCall(e)
            is ConstructorCall -> resolveConstructor(e)
            is Access -> {
                val obj = inferType(e.objectExpr)
                if (obj == Type.Error) Type.Error
                else if (obj is Type.Struct) {
                    structs[obj.name]?.fields?.find { it.first == e.member }?.second
                        ?: run { error("Field '${e.member}' not found", e.loc); Type.Error }
                } else { error("Cannot access member of non-struct", e.loc); Type.Error }
            }
            else -> Type.Unknown
        }
        e.resolvedType = t
        return t
    }

    private fun resolveConstructor(c: ConstructorCall): Type {
        val st = structs[c.typeName]
        if (st == null) {
            error("Unknown struct '${c.typeName}'", c.loc)
            return Type.Error
        }
        checkArgs(st.fields.map { it.second }, c.args, c.loc)
        return Type.Struct(c.typeName)
    }

    private fun resolveCall(c: Call): Type {
        externs[c.name]?.let {
            checkArgs(it.args.map { a -> a.second }, c.args, c.loc)
            return it.retType
        }

        val matches = functions.filter { it.name == c.name }
        for (fn in matches) {
            if (fn.extensionOf != null) {
                if (c.args.isNotEmpty()) {
                    val selfT = inferType(c.args[0])
                    if (selfT == Type.Error) return Type.Error

                    if (selfT == fn.extensionOf) {
                        if (areArgsValid(fn.args.map { it.second }, c.args.drop(1))) return fn.retType
                    }
                }
            } else {
                if (areArgsValid(fn.args.map { it.second }, c.args)) return fn.retType
            }
        }

        error("Function '${c.name}' not found", c.loc)
        return Type.Error
    }

    private fun checkArgs(expected: List<Type>, actual: List<Expr>, loc: Token) {
        if (!areArgsValid(expected, actual)) {
            if (actual.none { it.resolvedType == Type.Error })
                error("Argument mismatch", loc)
        }
    }

    private fun areArgsValid(expected: List<Type>, actual: List<Expr>): Boolean {
        if (expected.size != actual.size) return false
        for (i in expected.indices) {
            val act = inferType(actual[i])
            if (act == Type.Error) return true
            if (expected[i] != act) return false
        }
        return true
    }

    private fun lookup(n: String) = scopes.any { it.containsKey(n) }
}