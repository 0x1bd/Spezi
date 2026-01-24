package spezi.frontend.semantic

import spezi.common.diagnostic.CompilerException
import spezi.common.Context
import spezi.common.diagnostic.Level
import spezi.common.diagnostic.report
import spezi.domain.*

class SemanticAnalyzer(private val ctx: Context, private val prog: Program) {

    private val analysisCtx = AnalysisContext()
    private val structs = mutableMapOf<String, StructDef>()
    private val functions = mutableListOf<FnDef>()
    private val externs = mutableListOf<ExternFnDef>()
    private var hasError = false

    private fun error(msg: String) {
        ctx.report(Level.ERROR, msg)
        hasError = true
    }

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

        prog.elements.filterIsInstance<FnDef>()
            .firstOrNull { it.name == "main" } ?: run {
                
                error("Missing Main Function")
        }

        if (hasError) throw CompilerException("Analysis failed")
    }

    private fun checkFn(fn: FnDef) {
        analysisCtx.currentFunction = fn
        analysisCtx.enterScope()

        if (fn.extensionOf != null) {
            if (fn.extensionOf is Type.Struct && !structs.containsKey(fn.extensionOf.name)) {
                error("Extension struct not found")
            }

            analysisCtx.define("self", fn.extensionOf)
        }

        fn.args.forEach {
            if (analysisCtx.isDefinedInCurrentScope(it.first)) {
                error("Duplicate argument ${it.first}", fn.loc)
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
                    infer(s.cond)
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
        }

        if (s.init != null && s.type != null) {
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

        // TODO: check mutability
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
                error("Undefined '${e.name}'", e.loc)
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
            TokenType.BANG -> {
                if (inner != Type.Bool) {
                    error("Operator '!' requires bool, got ${inner.name}", e.loc)
                    Type.Error
                } else Type.Bool
            }

            TokenType.MINUS -> {
                if (!inner.isNumber()) {
                    error("Unary '-' requires number, got ${inner.name}", e.loc)
                    Type.Error
                } else inner
            }

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
            TokenType.EQEQ, TokenType.NEQ -> Type.Bool

            TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH -> {
                if (!l.isNumber()) {
                    error("Math op requires numbers", e.loc)
                    Type.Error
                } else l
            }

            else -> l
        }
    }

    private fun checkCast(c: CastExpr): Type {
        val from = infer(c.expr)
        val to = c.targetType

        if (from == Type.Error) return Type.Error

        if (from == to) {
            ctx.report(Level.WARN, "Cast is redundant", c.loc)
            return to
        }

        if (from.isNumber() && to.isNumber()) return to

        if (from == Type.Bool && to.isInt()) return to
        if (from.isInt() && to == Type.Bool) return to

        error("Cannot cast type '${from.name}' to '${to.name}'", c.loc)
        return Type.Error
    }

    private fun resolveCall(c: Call): Type {
        val argTypes = c.args.map { infer(it) }
        if (argTypes.any { it == Type.Error }) return Type.Error

        val externCandidates = externs.filter { it.name == c.name }
        for (ext in externCandidates) {
            val expected = ext.args.map { it.second }
            if (expected == argTypes) {
                return ext.retType
            }
        }

        val fnCandidates = functions.filter { it.name == c.name }
        for (fn in fnCandidates) {
            val expectedTypes = mutableListOf<Type>()
            if (fn.extensionOf != null) expectedTypes.add(fn.extensionOf)
            expectedTypes.addAll(fn.args.map { it.second })

            if (expectedTypes == argTypes) {
                return fn.retType
            }
        }

        if (externCandidates.isEmpty() && fnCandidates.isEmpty()) {
            error("Function '${c.name}' not found.", c.loc)
        } else {
            val sig = argTypes.joinToString(", ") { it.name }
            error("No matching overload for '${c.name}' with args ($sig)", c.loc)
        }

        return Type.Error
    }

    private fun resolveConstructor(c: ConstructorCall): Type {
        val st = structs[c.typeName]
        if (st == null) {
            error("Unknown struct '${c.typeName}'", c.loc)
            return Type.Error
        }

        val expectedTypes = st.fields.map { it.second }
        val argTypes = c.args.map { infer(it) }

        if (argTypes.any { it == Type.Error }) return Type.Error

        if (expectedTypes != argTypes) {
            val expStr = expectedTypes.joinToString { it.name }
            val gotStr = argTypes.joinToString { it.name }
            error("Constructor mismatch for '${c.typeName}'. Expected ($expStr), got ($gotStr)", c.loc)
            return Type.Error
        }

        return Type.Struct(c.typeName)
    }

    private fun resolveAccess(a: Access): Type {
        val objType = infer(a.objectExpr)
        if (objType == Type.Error) return Type.Error

        if (objType !is Type.Struct) {
            error("Cannot access member '${a.member}' on non-struct type '${objType.name}'", a.loc)
            return Type.Error
        }

        val def = structs[objType.name]
        if (def == null) {
            error("Struct definition for '${objType.name}' not found.", a.loc)
            return Type.Error
        }

        val field = def.fields.find { it.first == a.member }
        if (field == null) {
            error("Struct '${objType.name}' has no field named '${a.member}'", a.loc)
            return Type.Error
        }

        return field.second
    }

}