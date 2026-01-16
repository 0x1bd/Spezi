package spezi.frontend

import spezi.common.*
import spezi.domain.*

class Parser(private val ctx: Context) {

    private var lexer = Lexer(ctx)
    private var curr = lexer.next()
    private var prev = curr

    private fun advance() {
        prev = curr
        curr = lexer.next()
    }

    private fun consume(type: TokenType, msg: String) {
        if (curr.type == type) {
            advance()
            return
        }
        errorAtCurrent(msg)
    }

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false
        advance()
        return true
    }

    private fun check(type: TokenType): Boolean = curr.type == type

    private fun errorAtCurrent(msg: String): Nothing {
        ctx.reporter.error(msg, curr)
        throw CompilerException("Parse Error")
    }

    fun parseProgram(): Program {
        val startLoc = curr
        val nodes = mutableListOf<AstNode>()
        parseFileContent(nodes)
        return Program(nodes, startLoc)
    }

    private fun parseFileContent(nodes: MutableList<AstNode>) {
        while (!check(TokenType.EOF)) {
            try {
                when (curr.type) {
                    TokenType.IMPORT -> parseImport(nodes)
                    TokenType.STRUCT -> nodes.add(parseStruct())
                    TokenType.FN -> nodes.add(parseFn())
                    TokenType.EXTERN -> nodes.add(parseExtern())
                    else -> errorAtCurrent("Expected top-level declaration (fn, struct, extern, import)")
                }
            } catch (e: CompilerException) {
                throw e
            }
        }
    }

    private fun parseImport(nodes: MutableList<AstNode>) {
        advance()

        val sb = StringBuilder()
        if (!check(TokenType.ID)) errorAtCurrent("Expected module name")
        sb.append(curr.value)
        advance()

        while (match(TokenType.DOT)) {
            if (!check(TokenType.ID)) errorAtCurrent("Expected module part after '.'")
            sb.append(".")
            sb.append(curr.value)
            advance()
        }

        val importName = sb.toString()
        val path = ctx.resolveImport(importName)
            ?: errorAtCurrent("Could not resolve import '$importName'")

        if (ctx.isModuleLoaded(path)) {
            if (ctx.options.verbose) ctx.reporter.info("Recursive import skipped: $path")
            return
        }

        if (ctx.options.verbose) ctx.reporter.info("Importing module: $path")

        val prevSrc = ctx.source
        val prevLex = lexer
        val prevTok = curr

        ctx.source = SourceFile.fromPath(path)
        lexer = Lexer(ctx)
        curr = lexer.next()

        parseFileContent(nodes)

        ctx.source = prevSrc
        lexer = prevLex
        curr = prevTok
    }

    private fun parseStruct(): StructDef {
        val loc = curr
        consume(TokenType.STRUCT, "Expected 'struct'")

        if (!check(TokenType.ID)) errorAtCurrent("Expected struct name")
        val name = curr.value
        advance()

        consume(TokenType.LBRACE, "Expected '{'")
        val fields = mutableListOf<Pair<String, Type>>()

        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            if (!check(TokenType.ID)) errorAtCurrent("Expected field name")
            val fName = curr.value
            advance()

            consume(TokenType.COLON, "Expected ':'")
            val fType = parseType()
            fields.add(fName to fType)

            if (!check(TokenType.RBRACE)) {
                consume(TokenType.COMMA, "Expected ',' between fields")
            }
        }
        consume(TokenType.RBRACE, "Expected '}'")
        return StructDef(name, fields, loc)
    }

    private fun parseExtern(): ExternFnDef {
        val loc = curr
        consume(TokenType.EXTERN, "Expected 'extern'")
        consume(TokenType.FN, "Expected 'fn'")

        if (!check(TokenType.ID)) errorAtCurrent("Expected function name")
        val name = curr.value
        advance()

        val args = parseArgList()
        consume(TokenType.ARROW, "Expected '->'")
        val ret = parseType()

        return ExternFnDef(name, args, ret, loc)
    }

    private fun parseFn(): FnDef {
        val loc = curr
        consume(TokenType.FN, "Expected 'fn'")

        if (!check(TokenType.ID)) errorAtCurrent("Expected function name")
        var name = curr.value
        advance()

        var extensionOf: Type? = null
        if (match(TokenType.DOT)) {
            extensionOf = Type.Struct(name)
            if (!check(TokenType.ID)) errorAtCurrent("Expected method name")
            name = curr.value
            advance()
        }

        val args = parseArgList()
        consume(TokenType.ARROW, "Expected '->'")
        val ret = parseType()

        val body = parseBlock()
        return FnDef(name, extensionOf, args, ret, body, loc)
    }

    private fun parseArgList(): List<Pair<String, Type>> {
        consume(TokenType.LPAREN, "Expected '('")
        val args = mutableListOf<Pair<String, Type>>()
        if (!check(TokenType.RPAREN)) {
            do {
                if (!check(TokenType.ID)) errorAtCurrent("Expected argument name")
                val n = curr.value
                advance()
                consume(TokenType.COLON, "Expected ':'")
                val t = parseType()
                args.add(n to t)
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')'")
        return args
    }

    private fun parseType(): Type = when (curr.type) {
        TokenType.KW_I32 -> {
            advance(); Type.I32
        }

        TokenType.KW_I64 -> {
            advance(); Type.I64
        }

        TokenType.KW_F32 -> {
            advance(); Type.F32
        }

        TokenType.KW_F64 -> {
            advance(); Type.F64
        }

        TokenType.KW_BOOL -> {
            advance(); Type.Bool
        }

        TokenType.KW_STRING -> {
            advance(); Type.String
        }

        TokenType.KW_VOID -> {
            advance(); Type.Void
        }

        TokenType.ID -> {
            val t = Type.Struct(curr.value); advance(); t
        }

        else -> errorAtCurrent("Expected type")
    }

    private fun parseBlock(): Block {
        val loc = curr
        consume(TokenType.LBRACE, "Expected '{'")
        val stmts = mutableListOf<AstNode>()
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            stmts.add(parseStmt())
        }
        consume(TokenType.RBRACE, "Expected '}'")
        return Block(stmts, mutableListOf(), loc)
    }

    private fun parseStmt(): AstNode {
        if (match(TokenType.LET)) return parseVarDecl()
        if (match(TokenType.IF)) return parseIf()
        if (match(TokenType.RETURN)) return parseReturn()

        val expr = parseExpr()
        if (match(TokenType.EQ)) {
            if (expr !is VarRef) errorAtCurrent("Invalid assignment target")
            val valExpr = parseExpr()
            return Assign(expr.name, valExpr, expr.loc)
        }
        return expr
    }

    private fun parseVarDecl(): VarDecl {
        val loc = prev
        val isMut = match(TokenType.MUT)

        if (!check(TokenType.ID)) errorAtCurrent("Expected variable name")
        val name = curr.value
        advance()

        var type: Type? = null
        if (match(TokenType.COLON)) type = parseType()

        var init: Expr? = null
        if (match(TokenType.EQ)) init = parseExpr()

        return VarDecl(name, type, isMut, init, loc)
    }

    private fun parseIf(): IfStmt {
        val loc = prev
        val cond = parseExpr()
        val thenBlock = parseBlock()
        var elseBlock: Block? = null
        if (match(TokenType.ELSE)) {
            elseBlock = parseBlock()
        }
        return IfStmt(cond, thenBlock, elseBlock, loc)
    }

    private fun parseReturn(): ReturnStmt {
        val loc = prev
        if (check(TokenType.RBRACE) || check(TokenType.EOF)) {
            return ReturnStmt(null, loc)
        }
        val value = parseExpr()
        return ReturnStmt(value, loc)
    }

    private fun parseExpr(): Expr = parseBinOp(0)

    private fun getPrec(t: TokenType): Int = when (t) {
        TokenType.AS -> 11
        TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> 10
        TokenType.PLUS, TokenType.MINUS -> 9
        TokenType.EQEQ, TokenType.NEQ -> 6
        else -> -1
    }

    private fun parseBinOp(minPrec: Int): Expr {
        var lhs = parseUnary()
        while (true) {
            val prec = getPrec(curr.type)
            if (prec < minPrec) break

            val op = curr.type
            val loc = curr

            if (op == TokenType.AS) {
                advance()
                val targetType = parseType()
                lhs = CastExpr(lhs, targetType, loc)
                continue
            }

            advance()

            val rhs = parseBinOp(prec + 1)
            lhs = BinOp(lhs, op, rhs, loc)
        }
        return lhs
    }

    private fun parseUnary(): Expr {
        if (match(TokenType.BANG) || match(TokenType.MINUS)) {
            val op = prev.type
            val loc = prev
            val operand = parseUnary()

            return UnaryOp(op, operand, loc)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        val t = curr

        if (match(TokenType.INT_LIT)) {
            val text = t.value
            return if (text.endsWith("L")) LiteralInt(text.dropLast(1).toLong(), t).also { it.resolvedType = Type.I64 }
            else LiteralInt(text.toLong(), t)
        }

        if (match(TokenType.FLOAT_LIT)) {
            val text = t.value
            return if (text.endsWith("f")) LiteralFloat(text.dropLast(1).toDouble(), t).also {
                it.resolvedType = Type.F32
            }
            else LiteralFloat(text.toDouble(), t)
        }

        if (match(TokenType.STRING_LIT)) return LiteralString(t.value, t)
        if (match(TokenType.TRUE)) return LiteralBool(true, t)
        if (match(TokenType.FALSE)) return LiteralBool(false, t)

        if (match(TokenType.NEW)) {
            if (!check(TokenType.ID)) errorAtCurrent("Expected struct name after 'new'")
            val name = curr.value
            advance()
            val args = parseCallArgs()
            return ConstructorCall(name, args, t)
        }

        if (match(TokenType.LPAREN)) {
            val e = parseExpr()
            consume(TokenType.RPAREN, "Expected ')'")
            return e
        }

        if (match(TokenType.ID)) {
            var node: Expr = if (check(TokenType.LPAREN)) {
                Call(t.value, parseCallArgs(), t)
            } else {
                VarRef(t.value, t)
            }

            while (match(TokenType.DOT)) {
                val loc = prev
                if (!check(TokenType.ID)) errorAtCurrent("Expected member name")
                val member = curr.value
                advance()

                if (check(TokenType.LPAREN)) {
                    val rawArgs = parseCallArgs()
                    val argsWithSelf = mutableListOf<Expr>()
                    argsWithSelf.add(node)
                    argsWithSelf.addAll(rawArgs)
                    node = Call(member, argsWithSelf, loc)
                } else {
                    node = Access(node, member, loc)
                }
            }
            return node
        }

        errorAtCurrent("Unexpected token: ${t.value}")
    }

    private fun parseCallArgs(): List<Expr> {
        consume(TokenType.LPAREN, "Expected '('")
        val args = mutableListOf<Expr>()
        if (!check(TokenType.RPAREN)) {
            do {
                args.add(parseExpr())
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')'")
        return args
    }
}