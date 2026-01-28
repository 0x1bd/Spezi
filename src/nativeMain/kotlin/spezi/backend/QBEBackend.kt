package spezi.backend

import okio.FileSystem
import okio.Path.Companion.toPath
import spezi.common.*
import spezi.common.diagnostic.CompilerException
import spezi.domain.*

class QBEBackend(ctx: Context) : Disposable {

    private val emit = QbeEmitter()
    private val qCtx = QbeContext()

    override fun dispose() {
        emit.clear()
    }

    fun generate(p: Program) {
        qCtx.analyze(p)

        p.elements.filterIsInstance<StructDef>().forEach { s ->
            val fields = s.fields.map { mapTypeToQbe(it.second) }
            emit.emitType(s.name, fields)
        }

        p.elements.filterIsInstance<FnDef>().forEach { genFn(it) }

        qCtx.strings.forEach { (v, l) -> emit.emitData(l, v) }
    }

    private fun genFn(fn: FnDef) {
        val mangled = QbeNameMangler.mangle(
            fn.module, fn.name, fn.args.map { it.second }, fn.extensionOf, false
        )
        val ret = mapTypeToQbe(fn.retType)

        val args = mutableListOf<Pair<String, QType>>()
        if (fn.extensionOf != null) args.add("self_arg" to QType.Long)
        fn.args.forEach { args.add(it.first to mapTypeToQbe(it.second)) }

        emit.startFunction(fn.name == "main", mangled, args, ret)

        if (fn.extensionOf != null) {
            emit.allocNamed("self", 8)
            emit.store(QType.Long, "%.self_arg", "%self")
        }

        fn.args.forEach { (name, type) ->
            emit.allocNamed(name, qCtx.getTypeSize(type))
            emit.store(mapTypeToQbe(type), "%.$name", "%$name")
        }

        genBlock(fn.body)

        if (fn.retType == Type.Void) emit.ret()
        emit.endFunction()
    }

    private fun genBlock(b: Block) {
        b.stmts.forEach { genStmt(it) }
    }

    private fun genStmt(s: AstNode) {
        when (s) {
            is VarDecl -> {
                val t = s.type ?: s.init!!.resolvedType
                emit.allocNamed(s.name, qCtx.getTypeSize(t))
                if (s.init != null) {
                    emit.store(mapTypeToQbe(t), genExpr(s.init!!), "%${s.name}")
                }
            }
            is Assign -> {
                val t = mapTypeToQbe(s.value.resolvedType)
                emit.store(t, genExpr(s.value), "%${s.name}")
            }
            is ReturnStmt -> {
                if (s.value != null) emit.ret(genExpr(s.value)) else emit.ret()
            }
            is IfStmt -> {
                val cond = genExpr(s.cond)
                val thenLbl = emit.newLabel()
                val elseLbl = emit.newLabel()
                val endLbl = emit.newLabel()

                emit.jnz(cond, thenLbl, elseLbl)

                emit.emitLabel(thenLbl)
                genBlock(s.thenBlock)
                emit.jmp(endLbl)

                emit.emitLabel(elseLbl)
                s.elseBlock?.let { genBlock(it) }
                emit.jmp(endLbl)

                emit.emitLabel(endLbl)
            }
            is Expr -> genExpr(s)
            else -> {}
        }
    }

    private fun genExpr(e: Expr): String = when (e) {
        is LiteralInt -> e.value.toString()
        is LiteralFloat -> if (e.resolvedType == Type.F64) "d_${e.value}" else "s_${e.value}"
        is LiteralBool -> if (e.value) "1" else "0"
        is LiteralString -> qCtx.getStringLabel(e.value)

        is VarRef -> emit.load(mapTypeToQbe(e.resolvedType), "%${e.name}")

        is UnaryOp -> {
            val v = genExpr(e.operand)
            val t = mapTypeToQbe(e.resolvedType)
            when (e.op) {
                TokenType.MINUS -> emit.assign(t, "neg", v)
                TokenType.BANG -> emit.assign(QType.Word, "ceqw", v, "0")
                TokenType.TILDE -> emit.assign(t, "xor", v, "-1")
                else -> throw CompilerException("Unknown unary op")
            }
        }

        is BinOp -> genBinOp(e)
        is CastExpr -> genCast(e)

        is Call -> {
            val args = e.args.map {
                "${mapTypeToQbe(it.resolvedType)} ${genExpr(it)}"
            }
            val target = qCtx.resolveFunction(e.name, e.args.map { it.resolvedType })
            emit.call(target, args, mapTypeToQbe(e.resolvedType))
        }

        is ConstructorCall -> {
            val layout = qCtx.structLayouts[e.typeName]!!
            val ptr = emit.alloc(layout.size)
            val fields = layout.offsets.keys.toList()

            e.args.forEachIndexed { i, arg ->
                val offset = layout.offsets[fields[i]]!!
                val fieldPtr = emit.assign(QType.Long, "add", ptr, offset.toString())
                emit.store(mapTypeToQbe(arg.resolvedType), genExpr(arg), fieldPtr)
            }
            ptr
        }

        is Access -> {
            val base = genExpr(e.objectExpr)

            val structType = e.objectExpr.resolvedType as? Type.Struct
                ?: throw CompilerException("Cannot access member '${e.member}' on non-struct type ${e.objectExpr.resolvedType}")

            val layout = qCtx.structLayouts[structType.name]!!
            val offset = layout.offsets[e.member] ?: throw CompilerException("Unknown member ${e.member}")

            val fieldPtr = emit.assign(QType.Long, "add", base, offset.toString())

            emit.load(mapTypeToQbe(e.resolvedType), fieldPtr)
        }

        else -> throw CompilerException("Expression not supported: $e")
    }

    private fun genBinOp(e: BinOp): String {
        val l = genExpr(e.left)
        val r = genExpr(e.right)
        val t = mapTypeToQbe(e.left.resolvedType)
        val isFloat = e.left.resolvedType.isFloat()

        return when (e.op) {
            TokenType.PLUS -> emit.assign(t, "add", l, r)
            TokenType.MINUS -> emit.assign(t, "sub", l, r)
            TokenType.STAR -> emit.assign(t, "mul", l, r)
            TokenType.SLASH -> emit.assign(t, "div", l, r)
            TokenType.PERCENT -> {
                if (isFloat) throw CompilerException("Float modulo not supported")
                emit.assign(t, "rem", l, r)
            }

            TokenType.AMP -> emit.assign(t, "and", l, r)
            TokenType.PIPE -> emit.assign(t, "or", l, r)
            TokenType.CARET -> emit.assign(t, "xor", l, r)
            TokenType.LSHIFT -> emit.assign(t, "shl", l, r)
            TokenType.RSHIFT -> {
                if (isFloat) throw CompilerException("Float shift not supported")
                emit.assign(t, "sar", l, r)
            }

            TokenType.EQEQ -> emit.assign(QType.Word, if (isFloat) "ceq$t" else "ceqw", l, r)
            TokenType.NEQ -> emit.assign(QType.Word, if (isFloat) "cne$t" else "cnew", l, r)

            TokenType.LESS -> emit.assign(QType.Word, if (isFloat) "clt$t" else "cslt$t", l, r)
            TokenType.LESS_EQ -> emit.assign(QType.Word, if (isFloat) "cle$t" else "csle$t", l, r)
            TokenType.GREATER -> emit.assign(QType.Word, if (isFloat) "cgt$t" else "csgt$t", l, r)
            TokenType.GREATER_EQ -> emit.assign(QType.Word, if (isFloat) "cge$t" else "csge$t", l, r)

            else -> throw CompilerException("Op ${e.op} not implemented")
        }
    }

    private fun genCast(e: CastExpr): String {
        val v = genExpr(e.expr)
        val src = e.expr.resolvedType
        val dst = e.targetType

        if (src == dst) return v

        return if (src.isFloat() && dst.isFloat()) {
            if (src == Type.F32) emit.assign(QType.Double, "exts", v)
            else emit.assign(QType.Single, "truncd", v)
        }
        else if (src.isInt() && dst.isFloat()) {
            emit.assign(mapTypeToQbe(dst), "stof", v)
        }
        else if (src.isFloat() && dst.isInt()) {
            emit.assign(QType.Word, "ftosi", v)
        }
        else if (src.isInt() && dst.isInt()) {
            if (src == Type.I32 && dst == Type.I64) emit.assign(QType.Long, "extsw", v)
            else if (src == Type.I64 && dst == Type.I32) emit.assign(QType.Word, "copy", v)
            else emit.assign(mapTypeToQbe(dst), "copy", v)
        }
        else {
            emit.assign(mapTypeToQbe(dst), "copy", v)
        }
    }

    fun emitToFile(path: String) {
        FileSystem.SYSTEM.write(path.toPath()) {
            writeUtf8(emit.build())
        }
    }
}