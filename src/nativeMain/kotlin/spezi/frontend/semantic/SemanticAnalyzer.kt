package spezi.frontend.semantic

import spezi.common.diagnostic.CompilerException
import spezi.common.Context
import spezi.common.diagnostic.Level
import spezi.domain.*

class SemanticAnalyzer(private val ctx: Context, private val prog: Program) {

    private val analysisCtx = AnalysisContext()
    private val structs = mutableMapOf<String, StructDef>()
    private val functions = mutableListOf<FnDef>()
    private val externs = mutableListOf<ExternFnDef>()
    private var hasError = false

    private fun error(msg: String, loc: Token) {
        ctx.report(Level.ERROR, msg, loc)
        hasError = true
    }

    fun analyze() {
        prog.elements.forEach {
            when (it) {
                is StructDef -> structs[it.name] = it
                is FnDef -> functions.add(it)
                is ExternFnDef -> externs.add(it)

                else -> {}
            }
        }

        prog.elements.filterIsInstance<FnDef>().forEach { checkFn(it) }

        if (prog.elements.filterIsInstance<FnDef>().none { it.name == "main" }) {
            ctx.report(Level.ERROR, "Missing Main Function")
            hasError = true
        }
    }

    private fun checkFn(fn: FnDef) {
        analysisCtx.currentFunction = fn
        analysisCtx.enterScope()

        if (fn.extensionOf != null) {
            if (fn.extensionOf is Type.Struct && !structs.containsKey(fn.extensionOf.name)) {
                error("Extension struct '${fn.extensionOf.name}' not found", fn.loc)
            }
            analysisCtx.define("self", fn.extensionOf)
        }

        fn.args.forEach {
            if (analysisCtx.isDefinedInCurrentScope(it.first)) {
                error("Duplicate argument '${it.first}'", fn.loc)
            }
            analysisCtx.define(it.first, it.second)
        }

        checkBlock(fn.body)
        analysisCtx.exitScope()
        analysisCtx.currentFunction = null
    }

    private fun checkBlock(b: Block) {
        b.stmts.forEach { s ->
            when (s) {
                is VarDecl -> checkVarDecl(s, b)
                is Assign -> checkAssign(s)

                is IfStmt -> {
                    val condT = infer(s.cond)
                    if (condT != Type.Bool && condT != Type.Error) error("If condition must be bool", s.cond.loc)
                    checkBlock(s.thenBlock)
                    s.elseBlock?.let { checkBlock(it) }
                }

                is ReturnStmt -> checkReturn(s)
                is Expr -> infer(s)

                else -> {}
            }
        }
    }

    private fun checkVarDecl(s: VarDecl, b: Block) {
        val inferred = if (s.init != null) infer(s.init) else Type.Unknown
        val actualType = s.type ?: inferred

        if (actualType == Type.Unknown || actualType == Type.Void) {
            error("Cannot infer type for '${s.name}'", s.loc)
        } else if (s.init != null && s.type != null) {
            if (inferred != Type.Error && inferred != actualType) {
                error("Type mismatch. Expected ${actualType.name}, got ${inferred.name}", s.loc)
            }
        }
        analysisCtx.define(s.name, actualType)
        b.declaredVars.add(s)
    }

    private fun checkAssign(s: Assign) {
        val varType = analysisCtx.lookup(s.name)
        if (varType == null) {
            error("Undefined variable '${s.name}'", s.loc)
            return
        }
        val valType = infer(s.value)
        if (valType != Type.Error && varType != valType) {
            error("Assign mismatch: var is ${varType.name}, value is ${valType.name}", s.loc)
        }
    }

    private fun checkReturn(s: ReturnStmt) {
        val retType = analysisCtx.currentFunction?.retType ?: Type.Void
        val valType = if (s.value != null) infer(s.value) else Type.Void

        if (valType != Type.Error && valType != retType) {
            error("Return type mismatch. Expected ${retType.name}, got ${valType.name}", s.loc)
        }
    }

    private fun infer(e: Expr): Type {
        val t = when (e) {
            is LiteralInt -> e.resolvedType
            is LiteralFloat -> e.resolvedType
            is LiteralBool -> Type.Bool
            is LiteralString -> Type.String
            is VarRef -> analysisCtx.lookup(e.name) ?: run {
                error("Undefined variable '${e.name}'", e.loc)
                Type.Error
            }

            is BinOp -> checkBinOp(e)
            is UnaryOp -> checkUnaryOp(e)
            is CastExpr -> checkCast(e)
            is Call -> resolveCall(e)
            is ConstructorCall -> resolveConstructor(e)
            is Access -> resolveAccess(e)
            else -> Type.Unknown
        }
        e.resolvedType = t
        return t
    }

    private fun checkUnaryOp(e: UnaryOp): Type {
        val inner = infer(e.operand)
        if (inner == Type.Error) return Type.Error
        return when (e.op) {
            TokenType.BANG -> if (inner != Type.Bool) {
                error("! requires bool", e.loc); Type.Error
            } else Type.Bool

            TokenType.MINUS -> if (!inner.isNumber()) {
                error("- requires number", e.loc); Type.Error
            } else inner

            TokenType.TILDE -> if (!inner.isInt()) {
                error("~ requires integer", e.loc); Type.Error
            } else inner

            else -> Type.Error
        }
    }

    private fun checkBinOp(e: BinOp): Type {
        val l = infer(e.left)
        val r = infer(e.right)
        if (l == Type.Error || r == Type.Error) return Type.Error
        if (l != r) {
            error("Binary operand mismatch: ${l.name} vs ${r.name}", e.loc)
            return Type.Error
        }
        return when (e.op) {
            TokenType.EQEQ, TokenType.NEQ, TokenType.LESS, TokenType.GREATER, TokenType.LESS_EQ, TokenType.GREATER_EQ -> Type.Bool
            TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH -> if (!l.isNumber()) {
                error("Math op requires numbers", e.loc); Type.Error
            } else l

            TokenType.AMP, TokenType.PIPE, TokenType.CARET, TokenType.LSHIFT, TokenType.RSHIFT -> if (!l.isInt()) {
                error("Bitwise op requires int", e.loc); Type.Error
            } else l

            else -> l
        }
    }

    private fun checkCast(c: CastExpr): Type {
        val from = infer(c.expr)
        val to = c.targetType
        if (from == Type.Error) return Type.Error
        if (from == to) {
            ctx.report(Level.WARN, "Cast is redundant", c.loc); return to
        }
        if (from.isNumber() && to.isNumber()) return to
        if ((from == Type.Bool && to.isInt()) || (from.isInt() && to == Type.Bool)) return to
        error("Cannot cast '${from.name}' to '${to.name}'", c.loc)
        return Type.Error
    }

    private fun resolveCall(c: Call): Type {
        val argTypes = c.args.map { infer(it) }
        if (argTypes.any { it == Type.Error }) return Type.Error

        externs.find { it.name == c.name && it.args.map { a -> a.second } == argTypes }?.let { return it.retType }

        functions.find {
            val expected =
                (if (it.extensionOf != null) listOf(it.extensionOf) else emptyList()) + it.args.map { a -> a.second }
            it.name == c.name && expected == argTypes
        }?.let { return it.retType }

        error("Function '${c.name}' with args (${argTypes.joinToString { it.name }}) not found.", c.loc)
        return Type.Error
    }

    private fun resolveConstructor(c: ConstructorCall): Type {
        val st = structs[c.typeName] ?: run { error("Unknown struct '${c.typeName}'", c.loc); return Type.Error }
        val expected = st.fields.map { it.second }
        val args = c.args.map { infer(it) }
        if (args.any { it == Type.Error }) return Type.Error
        if (expected != args) {
            error(
                "Constructor mismatch. Expected (${expected.joinToString { it.name }}), got (${args.joinToString { it.name }})",
                c.loc
            )
            return Type.Error
        }
        return Type.Struct(c.typeName)
    }

    private fun resolveAccess(a: Access): Type {
        val obj = infer(a.objectExpr)
        if (obj == Type.Error) return Type.Error
        if (obj !is Type.Struct) {
            error("Dot access on non-struct '${obj.name}'", a.loc); return Type.Error
        }
        val def = structs[obj.name] ?: return Type.Error
        val field = def.fields.find { it.first == a.member }
        if (field == null) {
            error("Field '${a.member}' not found on '${obj.name}'", a.loc); return Type.Error
        }
        return field.second
    }
}