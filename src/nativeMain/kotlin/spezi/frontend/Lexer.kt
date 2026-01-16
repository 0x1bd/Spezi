package spezi.frontend

import spezi.common.CompilerException
import spezi.common.Context
import spezi.domain.Token
import spezi.domain.TokenType

class Lexer(private val ctx: Context) {

    private val src = ctx.source.content
    private var start = 0
    private var current = 0
    private var line = 1
    private var lineStart = 0

    private val keywords = mapOf(
        "let" to TokenType.LET,
        "mut" to TokenType.MUT,
        "fn" to TokenType.FN,
        "struct" to TokenType.STRUCT,
        "import" to TokenType.IMPORT,
        "if" to TokenType.IF,
        "else" to TokenType.ELSE,
        "return" to TokenType.RETURN,
        "extern" to TokenType.EXTERN,
        "new" to TokenType.NEW,
        "as" to TokenType.AS,
        "void" to TokenType.KW_VOID,
        "i32" to TokenType.KW_I32,
        "i64" to TokenType.KW_I64,
        "f32" to TokenType.KW_F32,
        "f64" to TokenType.KW_F64,
        "bool" to TokenType.KW_BOOL,
        "string" to TokenType.KW_STRING,
        "true" to TokenType.TRUE,
        "false" to TokenType.FALSE
    )

    fun next(): Token {
        skipWhitespace()
        start = current

        if (isAtEnd()) return makeToken(TokenType.EOF)

        val c = advance()

        if (c.isLetter() || c == '_') return scanIdentifier()
        if (c.isDigit()) return scanNumber()
        if (c == '"') return scanString()

        return when (c) {
            '(' -> makeToken(TokenType.LPAREN)
            ')' -> makeToken(TokenType.RPAREN)
            '{' -> makeToken(TokenType.LBRACE)
            '}' -> makeToken(TokenType.RBRACE)
            ',' -> makeToken(TokenType.COMMA)
            '.' -> makeToken(TokenType.DOT)
            ':' -> makeToken(TokenType.COLON)
            '-' -> if (match('>')) makeToken(TokenType.ARROW) else makeToken(TokenType.MINUS)
            '+' -> makeToken(TokenType.PLUS)
            '*' -> makeToken(TokenType.STAR)
            '/' -> makeToken(TokenType.SLASH)
            '%' -> makeToken(TokenType.PERCENT)
            '&' -> makeToken(TokenType.AMP)
            '|' -> makeToken(TokenType.PIPE)
            '^' -> makeToken(TokenType.CARET)
            '~' -> makeToken(TokenType.TILDE)

            '!' -> if (match('=')) makeToken(TokenType.NEQ) else makeToken(TokenType.BANG)
            '=' -> if (match('=')) makeToken(TokenType.EQEQ) else makeToken(TokenType.EQ)
            '<' -> if (match('<')) makeToken(TokenType.LSHIFT) else error("Unexpected '<'")
            '>' -> if (match('>')) makeToken(TokenType.RSHIFT) else error("Unexpected '>'")

            else -> error("Unexpected character: '$c'")
        }
    }

    private fun scanIdentifier(): Token {
        while (peek().isLetterOrDigit() || peek() == '_') advance()

        val text = src.substring(start, current)
        val type = keywords[text] ?: TokenType.ID
        return makeToken(type)
    }

    private fun scanNumber(): Token {
        while (peek().isDigit()) advance()

        if (peek() == '.' && peek(1).isDigit()) {
            advance()
            while (peek().isDigit()) advance()

            if (peek() == 'f') {
                advance()
                return makeToken(TokenType.FLOAT_LIT)
            }
            return makeToken(TokenType.FLOAT_LIT)
        }

        if (peek() == 'L') {
            advance()
            return makeToken(TokenType.INT_LIT)
        }

        if (peek() == 'f') {
            advance()
            return makeToken(TokenType.FLOAT_LIT)
        }

        return makeToken(TokenType.INT_LIT)
    }

    private fun scanString(): Token {
        val sb = StringBuilder()
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') {
                line++; lineStart = current
            }

            val c = advance()
            if (c == '\\') {
                if (isAtEnd()) error("Unterminated string")
                when (val esc = advance()) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }

        if (isAtEnd()) error("Unterminated string literal")
        advance()

        return Token(TokenType.STRING_LIT, sb.toString(), ctx.source, line, start - lineStart + 1, current - start)
    }

    private fun skipWhitespace() {
        while (true) {
            val c = peek()
            when {
                c == ' ' || c == '\r' || c == '\t' -> advance()

                c == '\n' -> {
                    line++
                    advance()
                    lineStart = current
                }

                c == '/' && peek(1) == '/' -> {
                    while (peek() != '\n' && !isAtEnd()) advance()
                }

                else -> return
            }
        }
    }

    private fun advance(): Char {
        current++
        return src[current - 1]
    }

    private fun peek(offset: Int = 0): Char {
        if (current + offset >= src.length) return '\u0000'
        return src[current + offset]
    }

    private fun match(expected: Char): Boolean {
        if (isAtEnd()) return false
        if (src[current] != expected) return false
        current++
        return true
    }

    private fun isAtEnd() = current >= src.length

    private fun makeToken(type: TokenType): Token {
        val text = src.substring(start, current)
        return Token(type, text, ctx.source, line, start - lineStart + 1, text.length)
    }

    private fun error(msg: String): Nothing {
        val col = start - lineStart + 1
        ctx.reporter.error(msg, Token(TokenType.EOF, "", ctx.source, line, col, 1))
        throw CompilerException("Lexing failed")
    }
}