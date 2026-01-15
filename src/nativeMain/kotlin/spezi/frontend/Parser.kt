package spezi.frontend

import spezi.common.CompilerException
import spezi.common.Context
import spezi.domain.*

class Parser(private val ctx: Context) {

    private val lexer = Lexer(ctx)
    private var curr: Token = lexer.next()

    private fun eat() {
        curr = lexer.next()
    }

    private fun consume() = curr.also { eat() }
    private fun error(msg: String): Nothing {
        ctx.reporter.error(msg, curr, ctx.source); throw CompilerException("Parse failed")
    }

    private fun expect(type: TokenType) =
        if (curr.type == type) consume() else error("Expected ${type.name}, got ${curr.value}")

    fun parseProgram(): Program {
        val loc = curr
        val nodes = mutableListOf<AstNode>()
        while (curr.type != TokenType.EOF) {
            when (curr.type) {
                TokenType.IMPORT -> while (curr.type != TokenType.STRUCT && curr.type != TokenType.FN && curr.type != TokenType.EXTERN && curr.type != TokenType.EOF) eat()
                TokenType.STRUCT -> nodes.add(parseStruct())
                TokenType.FN -> nodes.add(parseFn())
                TokenType.EXTERN -> nodes.add(parseExtern())
                else -> error("Unexpected top-level token")
            }
        }
        return Program(nodes, loc)
    }

    private fun parseType(): Type = when (curr.type) {
        TokenType.KW_I32 -> {
            eat(); Type.I32
        }

        TokenType.KW_BOOL -> {
            eat(); Type.Bool
        }

        TokenType.KW_STRING -> {
            eat(); Type.String
        }

        TokenType.KW_VOID -> {
            eat(); Type.Void
        }

        TokenType.ID -> Type.Struct(consume().value)
        else -> error("Expected type")
    }

    private fun parseStruct() = StructDef(
        expect(TokenType.STRUCT).let { expect(TokenType.ID).value },
        mutableListOf<Pair<String, Type>>().also { f ->
            expect(TokenType.LBRACE)
            while (curr.type != TokenType.RBRACE) {
                val n = expect(TokenType.ID).value; expect(TokenType.COLON);
                val t = parseType()
                f.add(n to t)
                if (curr.type == TokenType.COMMA) eat()
            }
            expect(TokenType.RBRACE)
        },
        curr
    )

    private fun parseExtern(): ExternFnDef {
        val loc = expect(TokenType.EXTERN)
        expect(TokenType.FN)
        val name = expect(TokenType.ID).value
        expect(TokenType.LPAREN)
        val args = mutableListOf<Pair<String, Type>>()
        while (curr.type != TokenType.RPAREN) {
            val n = expect(TokenType.ID).value; expect(TokenType.COLON);
            val t = parseType()
            args.add(n to t)
            if (curr.type == TokenType.COMMA) eat()
        }
        expect(TokenType.RPAREN)
        expect(TokenType.ARROW)
        return ExternFnDef(name, args, parseType(), loc)
    }

    private fun parseFn(): FnDef {
        val loc = expect(TokenType.FN)
        var name = expect(TokenType.ID).value
        var ext: Type? = null
        if (curr.type == TokenType.DOT) {
            eat(); ext = Type.Struct(name); name = expect(TokenType.ID).value
        }
        expect(TokenType.LPAREN)
        val args = mutableListOf<Pair<String, Type>>()
        while (curr.type != TokenType.RPAREN) {
            val n = expect(TokenType.ID).value; expect(TokenType.COLON);
            val t = parseType()
            args.add(n to t)
            if (curr.type == TokenType.COMMA) eat()
        }
        expect(TokenType.RPAREN)
        expect(TokenType.ARROW)
        val ret = parseType()
        return FnDef(name, ext, args, ret, parseBlock(), loc)
    }

    private fun parseBlock(): Block {
        val loc = expect(TokenType.LBRACE)
        val stmts = mutableListOf<AstNode>()
        while (curr.type != TokenType.RBRACE && curr.type != TokenType.EOF) stmts.add(parseStmt())
        expect(TokenType.RBRACE)
        return Block(stmts, mutableListOf(), loc)
    }

    private fun parseStmt(): AstNode = when (curr.type) {
        TokenType.LET -> {
            val loc = consume()
            val mut = if (curr.type == TokenType.MUT) {
                eat(); true
            } else false
            val name = expect(TokenType.ID).value
            val type = if (curr.type == TokenType.COLON) {
                eat(); parseType()
            } else null
            val init = if (curr.type == TokenType.EQ) {
                eat(); parseExpr()
            } else null
            VarDecl(name, type, mut, init, loc)
        }

        TokenType.IF -> {
            val loc = consume();
            val c = parseExpr();
            val t = parseBlock()
            val e = if (curr.type == TokenType.ELSE) {
                eat(); parseBlock()
            } else null
            IfStmt(c, t, e, loc)
        }

        TokenType.RETURN -> ReturnStmt(
            if (consume().type != TokenType.RBRACE && curr.type != TokenType.RBRACE) parseExpr() else null,
            curr
        )

        else -> {
            val expr = parseExpr()
            if (curr.type == TokenType.EQ) {
                if (expr !is VarRef) error("Invalid assignment")
                eat(); Assign(expr.name, parseExpr(), expr.loc)
            } else expr
        }
    }

    private fun parseExpr() = parseBinOp(0)
    private fun parseBinOp(prec: Int): Expr {
        var left = parsePrimary()
        while (true) {
            val p = when (curr.type) {
                TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> 10
                TokenType.PLUS, TokenType.MINUS -> 9
                TokenType.EQEQ, TokenType.NEQ -> 6
                else -> -1
            }
            if (p < prec) return left
            val op = consume().type
            left = BinOp(left, op, parseBinOpRecursive(parsePrimary(), p + 1), curr)
        }
    }

    private fun parseBinOpRecursive(lhs: Expr, minPrec: Int): Expr {
        var left = lhs
        while (true) {
            val p = when (curr.type) {
                TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> 10
                TokenType.PLUS, TokenType.MINUS -> 9
                TokenType.EQEQ, TokenType.NEQ -> 6
                else -> -1
            }
            if (p < minPrec) return left
            val op = consume().type
            val nextP = when (curr.type) {
                TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> 10
                TokenType.PLUS, TokenType.MINUS -> 9
                TokenType.EQEQ, TokenType.NEQ -> 6
                else -> -1
            }
            var right = parsePrimary()
            if (p < nextP) right = parseBinOpRecursive(right, p + 1)
            left = BinOp(left, op, right, curr)
        }
    }

    private fun parsePrimary(): Expr {
        // ENFORCE NEW: parse 'new Vector()' as ConstructorCall
        if (curr.type == TokenType.NEW) {
            eat()
            val name = expect(TokenType.ID).value
            expect(TokenType.LPAREN)
            val args = mutableListOf<Expr>()
            if (curr.type != TokenType.RPAREN) do {
                args.add(parseExpr())
                if (curr.type == TokenType.COMMA) eat()
            } while (curr.type != TokenType.RPAREN)
            expect(TokenType.RPAREN)
            return ConstructorCall(name, args, curr)
        }
        val t = curr
        if (t.type == TokenType.INT_LIT) {
            eat(); return LiteralInt(t.value.toInt(), t)
        }
        if (t.type == TokenType.STRING_LIT) {
            eat(); return LiteralString(t.value, t)
        }
        if (t.type == TokenType.TRUE) {
            eat(); return LiteralBool(true, t)
        }
        if (t.type == TokenType.FALSE) {
            eat(); return LiteralBool(false, t)
        }
        if (t.type == TokenType.ID) {
            val name = t.value; eat()
            var node: Expr = if (curr.type == TokenType.LPAREN) {
                val args = mutableListOf<Expr>()
                eat()
                if (curr.type != TokenType.RPAREN) do {
                    args.add(parseExpr())
                    if (curr.type == TokenType.COMMA) eat()
                } while (curr.type != TokenType.RPAREN)
                expect(TokenType.RPAREN)
                Call(name, args, t)
            } else VarRef(name, t)

            while (curr.type == TokenType.DOT) {
                val loc = consume()
                val mem = expect(TokenType.ID).value
                if (curr.type == TokenType.LPAREN) {
                    val args = mutableListOf<Expr>(); args.add(node) // Implicit Self
                    eat()
                    if (curr.type != TokenType.RPAREN) do {
                        args.add(parseExpr())
                        if (curr.type == TokenType.COMMA) eat()
                    } while (curr.type != TokenType.RPAREN)
                    expect(TokenType.RPAREN)
                    node = Call(mem, args, loc)
                } else node = Access(node, mem, loc)
            }
            return node
        }
        if (t.type == TokenType.LPAREN) {
            eat();
            val e = parseExpr(); expect(TokenType.RPAREN); return e
        }
        error("Unexpected token ${t.value}")
    }
}
