package spezi.frontend

import spezi.common.*
import spezi.common.diagnostic.Level
import spezi.domain.*

class Parser(private val ctx: Context) {

    private var lexer = Lexer(ctx)
    private var curr = lexer.next()
    private var prev = curr

    private class ParseError : RuntimeException()

    fun parseProgram(): Program {
        val startLoc = curr
        val nodes = mutableListOf<AstNode>()
        parseFileContent(nodes, moduleName = "")
        return Program(nodes, startLoc)
    }

    private fun parseFileContent(nodes: MutableList<AstNode>, moduleName: String) {
        while (!check(TokenType.EOF)) {
            try {
                when (curr.type) {
                    TokenType.IMPORT -> parseImport(nodes)
                    TokenType.STRUCT -> nodes.add(parseStruct(moduleName))
                    TokenType.FN -> nodes.add(parseFn(moduleName))
                    TokenType.EXTERN -> nodes.add(parseExtern())

                    else -> {
                        errorAtCurrent("Expected top-level declaration")
                    }
                }
            } catch (e: ParseError) {
                synchronize()
            }
        }
    }

    private fun synchronize() {
        advance()
        while (!check(TokenType.EOF)) {
            when (curr.type) {
                TokenType.FN, TokenType.STRUCT, TokenType.LET,
                TokenType.IMPORT, TokenType.IF, TokenType.RETURN,
                TokenType.EXTERN -> return

                else -> advance()
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
            if (!check(TokenType.ID)) errorAtCurrent("Expected part after '.'")
            sb.append(".")
            sb.append(curr.value)
            advance()
        }

        val importName = sb.toString()
        val path = ctx.resolveImport(importName)
        if (path == null) {
            ctx.report(Level.ERROR, "Could not resolve '$importName'", prev)
            return
        }

        if (ctx.isModuleLoaded(path)) return

        val prevSrc = ctx.currentSource
        val prevLex = lexer
        val prevTok = curr

        ctx.currentSource = SourceFile.fromPath(path)
        lexer = Lexer(ctx)
        curr = lexer.next()

        parseFileContent(nodes, moduleName = importName)

        ctx.currentSource = prevSrc
        lexer = prevLex
        curr = prevTok
    }

    private fun parseStruct(mod: String): StructDef {
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
            if (!check(TokenType.RBRACE)) consume(TokenType.COMMA, "Expected ','")
        }
        consume(TokenType.RBRACE, "Expected '}'")
        return StructDef(mod, name, fields, loc)
    }

    private fun parseFn(mod: String): FnDef {
        val loc = curr
        consume(TokenType.FN, "Expected 'fn'")
        if (!check(TokenType.ID)) errorAtCurrent("Expected function name")
        var name = curr.value
        advance()

        var extensionOf: Type? = null
        if (match(TokenType.DOT)) {
            extensionOf = Type.Struct(name)
            if (!check(TokenType.ID)) errorAtCurrent("Expected function name after receiver")
            name = curr.value
            advance()
        }

        val args = parseArgList()
        consume(TokenType.ARROW, "Expected '->'")
        val ret = parseType()
        val body = parseBlock()
        return FnDef(mod, name, extensionOf, args, ret, body, loc)
    }

    private fun parseExtern(): ExternFnDef {
        val loc = curr
        consume(TokenType.EXTERN, "extern")
        consume(TokenType.FN, "fn")
        if (!check(TokenType.ID)) errorAtCurrent("Expected function name")
        val name = curr.value
        advance()
        val args = parseArgList()
        consume(TokenType.ARROW, "->")
        val ret = parseType()
        return ExternFnDef(name, args, ret, loc)
    }

    private fun parseBlock(): Block {
        val loc = curr
        consume(TokenType.LBRACE, "{")
        val stmts = mutableListOf<AstNode>()
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            try {
                stmts.add(parseStmt())
            } catch (e: ParseError) {
                synchronizeStmt()
            }
        }
        consume(TokenType.RBRACE, "}")
        return Block(stmts, mutableListOf(), loc)
    }

    private fun synchronizeStmt() {
        advance()
        while (!check(TokenType.EOF) && !check(TokenType.RBRACE)) {
            if (curr.type == TokenType.LET || curr.type == TokenType.IF || curr.type == TokenType.RETURN) return
            advance()
        }
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
        val mut = match(TokenType.MUT)
        if (!check(TokenType.ID)) errorAtCurrent("Expected variable name")
        val name = curr.value
        advance()

        var type: Type? = null
        if (match(TokenType.COLON)) type = parseType()

        var init: Expr? = null
        if (match(TokenType.EQ)) init = parseExpr()

        return VarDecl(name, type, mut, init, loc)
    }

    private fun parseIf(): IfStmt {
        val loc = prev
        val cond = parseExpr()
        val thenBlock = parseBlock()
        val elseBlock = if (match(TokenType.ELSE)) parseBlock() else null
        return IfStmt(cond, thenBlock, elseBlock, loc)
    }

    private fun parseReturn(): ReturnStmt {
        val loc = prev
        if (check(TokenType.RBRACE) || check(TokenType.EOF)) return ReturnStmt(null, loc)
        return ReturnStmt(parseExpr(), loc)
    }

    private fun parseExpr(): Expr = parseBinOp(0)

    private fun getPrec(t: TokenType): Int = when (t) {
        TokenType.AS -> 11
        TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> 10
        TokenType.PLUS, TokenType.MINUS -> 9
        TokenType.LSHIFT, TokenType.RSHIFT -> 8
        TokenType.LESS, TokenType.LESS_EQ, TokenType.GREATER, TokenType.GREATER_EQ -> 7
        TokenType.EQEQ, TokenType.NEQ -> 6
        TokenType.AMP -> 5
        TokenType.CARET -> 4
        TokenType.PIPE -> 3
        else -> -1
    }

    private fun parseBinOp(minPrec: Int): Expr {
        var lhs = parseUnary()
        while (true) {
            val prec = getPrec(curr.type)
            if (prec < minPrec) break
            val op = curr.type
            val loc = curr
            advance()

            if (op == TokenType.AS) {
                val targetType = parseType()
                lhs = CastExpr(lhs, targetType, loc)
            } else {
                val rhs = parseBinOp(prec + 1)
                lhs = BinOp(lhs, op, rhs, loc)
            }
        }
        return lhs
    }

    private fun parseUnary(): Expr {
        if (match(TokenType.BANG) || match(TokenType.MINUS) || match(TokenType.TILDE)) {
            return UnaryOp(prev.type, parseUnary(), prev)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        val t = curr
        if (match(TokenType.INT_LIT)) {
            val isLong = t.value.endsWith("L")
            val valStr = if (isLong) t.value.dropLast(1) else t.value
            return LiteralInt(valStr.toLong(), t).apply { if (isLong) resolvedType = Type.I64 }
        }
        if (match(TokenType.FLOAT_LIT)) {
            val isF32 = t.value.endsWith("f")
            val valStr = if (isF32) t.value.dropLast(1) else t.value
            return LiteralFloat(valStr.toDouble(), t).apply { if (isF32) resolvedType = Type.F32 }
        }
        if (match(TokenType.STRING_LIT)) return LiteralString(t.value, t)
        if (match(TokenType.TRUE)) return LiteralBool(true, t)
        if (match(TokenType.FALSE)) return LiteralBool(false, t)

        if (match(TokenType.NEW)) {
            if (!check(TokenType.ID)) errorAtCurrent("Expected type name after 'new'")
            val name = curr.value
            advance()
            return ConstructorCall(name, parseCallArgs(), t)
        }
        if (match(TokenType.LPAREN)) {
            val e = parseExpr()
            consume(TokenType.RPAREN, "Expected ')'")
            return e
        }
        if (match(TokenType.ID)) {
            var node: Expr = if (check(TokenType.LPAREN)) Call(t.value, parseCallArgs(), t) else VarRef(t.value, t)
            while (match(TokenType.DOT)) {
                val loc = prev
                if (!check(TokenType.ID)) errorAtCurrent("Expected member name")
                val member = curr.value
                advance()
                node = if (check(TokenType.LPAREN)) {
                    val args = mutableListOf(node)
                    args.addAll(parseCallArgs())
                    Call(member, args, loc)
                } else Access(node, member, loc)
            }
            return node
        }
        errorAtCurrent("Unexpected token: ${t.type}")
    }

    private fun parseCallArgs(): List<Expr> {
        consume(TokenType.LPAREN, "Expected '('")
        val list = mutableListOf<Expr>()
        if (!check(TokenType.RPAREN)) {
            do {
                list.add(parseExpr())
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')'")
        return list
    }

    private fun parseArgList(): List<Pair<String, Type>> {
        consume(TokenType.LPAREN, "Expected '('")
        val args = mutableListOf<Pair<String, Type>>()
        if (!check(TokenType.RPAREN)) {
            do {
                if (!check(TokenType.ID)) errorAtCurrent("Expected argument name")
                val name = curr.value
                advance()
                consume(TokenType.COLON, "Expected ':'")
                val type = parseType()
                args.add(name to type)
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

    private fun advance() {
        prev = curr; curr = lexer.next()
    }

    private fun consume(type: TokenType, msg: String) {
        if (curr.type == type) advance() else errorAtCurrent(msg)
    }

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false; advance(); return true
    }

    private fun check(type: TokenType) = curr.type == type

    private fun errorAtCurrent(msg: String): Nothing {
        ctx.report(Level.ERROR, msg, curr)
        throw ParseError()
    }
}