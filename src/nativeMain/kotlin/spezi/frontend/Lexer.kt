package spezi.frontend

import spezi.common.Context
import spezi.common.SourceFile
import spezi.common.diagnostic.Level
import spezi.domain.Token
import spezi.domain.TokenType

class Lexer(private val ctx: Context) {

    private val src = ctx.currentSource.content
    private var start = 0
    private var current = 0
    private var line = 1
    private var lineStart = 0

    private val keywords = mapOf(
        "let" to TokenType.LET, "mut" to TokenType.MUT, "fn" to TokenType.FN,
        "struct" to TokenType.STRUCT, "import" to TokenType.IMPORT,
        "if" to TokenType.IF, "else" to TokenType.ELSE, "return" to TokenType.RETURN,
        "extern" to TokenType.EXTERN, "new" to TokenType.NEW, "as" to TokenType.AS,
        "void" to TokenType.KW_VOID, "bool" to TokenType.KW_BOOL, "string" to TokenType.KW_STRING,
        "i32" to TokenType.KW_I32, "i64" to TokenType.KW_I64,
        "f32" to TokenType.KW_F32, "f64" to TokenType.KW_F64,
        "true" to TokenType.TRUE, "false" to TokenType.FALSE
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
            '+' -> makeToken(TokenType.PLUS)
            '*' -> makeToken(TokenType.STAR)
            '/' -> makeToken(TokenType.SLASH)
            '%' -> makeToken(TokenType.PERCENT)
            '^' -> makeToken(TokenType.CARET)
            '~' -> makeToken(TokenType.TILDE)
            '-' -> if (match('>')) makeToken(TokenType.ARROW) else makeToken(TokenType.MINUS)
            '!' -> if (match('=')) makeToken(TokenType.NEQ) else makeToken(TokenType.BANG)
            '=' -> if (match('=')) makeToken(TokenType.EQEQ) else makeToken(TokenType.EQ)
            '&' -> if (match('&')) {
                errorToken("&& not supported, use 'and'"); next()
            } else makeToken(TokenType.AMP)

            '|' -> if (match('|')) {
                errorToken("|| not supported, use 'or'"); next()
            } else makeToken(TokenType.PIPE)

            '<' -> when {
                match('<') -> makeToken(TokenType.LSHIFT)
                match('=') -> makeToken(TokenType.LESS_EQ)
                else -> makeToken(TokenType.LESS)
            }

            '>' -> when {
                match('>') -> makeToken(TokenType.RSHIFT)
                match('=') -> makeToken(TokenType.GREATER_EQ)
                else -> makeToken(TokenType.GREATER)
            }

            else -> {
                errorToken("Unexpected character: '$c'")
                next()
            }
        }
    }

    private fun scanIdentifier(): Token {
        while (peek().isLetterOrDigit() || peek() == '_') advance()
        val text = src.substring(start, current)
        return makeToken(keywords[text] ?: TokenType.ID)
    }

    private fun scanNumber(): Token {
        while (peek().isDigit()) advance()
        if (peek() == '.' && peek(1).isDigit()) {
            advance()
            while (peek().isDigit()) advance()
            if (peek() == 'f') advance()
            return makeToken(TokenType.FLOAT_LIT)
        }
        if (peek() == 'f') {
            advance(); return makeToken(TokenType.FLOAT_LIT)
        }
        if (peek() == 'L') {
            advance(); return makeToken(TokenType.INT_LIT)
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
                if (isAtEnd()) {
                    errorToken("Unterminated string"); break
                }
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
        if (isAtEnd()) errorToken("Unterminated string literal") else advance()
        return Token(
            TokenType.STRING_LIT,
            sb.toString(),
            ctx.currentSource,
            line,
            start - lineStart + 1,
            current - start
        )
    }

    private fun skipWhitespace() {
        while (true) {
            val c = peek()
            when (c) {
                ' ', '\r', '\t' -> advance()

                '\n' -> {
                    line++; advance(); lineStart = current
                }

                '/' -> if (peek(1) == '/') while (peek() != '\n' && !isAtEnd()) advance() else return
                else -> return
            }
        }
    }

    private fun advance(): Char {
        current++; return src[current - 1]
    }

    private fun peek(offset: Int = 0): Char = if (current + offset >= src.length) '\u0000' else src[current + offset]
    private fun match(expected: Char): Boolean = if (isAtEnd() || src[current] != expected) false else {
        current++; true
    }

    private fun isAtEnd() = current >= src.length
    private fun makeToken(type: TokenType) =
        Token(type, src.substring(start, current), ctx.currentSource, line, start - lineStart + 1, current - start)

    private fun errorToken(msg: String) {
        ctx.report(Level.ERROR, msg, Token(TokenType.EOF, "", ctx.currentSource, line, start - lineStart + 1, 1))
    }
}