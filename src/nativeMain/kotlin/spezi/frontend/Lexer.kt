package spezi.frontend

import spezi.common.CompilerException
import spezi.common.Context
import spezi.domain.Token
import spezi.domain.TokenType

class Lexer(private val ctx: Context) {
    private val src = ctx.source.content
    private var pos = 0
    private var line = 1
    private var lineStart = 0

    private fun peek(offset: Int = 0): Char = if (pos + offset < src.length) src[pos + offset] else '\u0000'
    private fun advance(): Char {
        val c = peek()
        pos++
        if (c == '\n') { line++; lineStart = pos }
        return c
    }
    private fun error(msg: String): Nothing {
        val col = pos - lineStart + 1
        ctx.reporter.error(msg, Token(TokenType.EOF, "", line, col, 1), ctx.source)
        throw CompilerException("Lexing failed")
    }
    private fun makeToken(type: TokenType, value: String, startPos: Int, startLine: Int, startCol: Int) =
        Token(type, value, startLine, startCol, value.length)

    fun next(): Token {
        while (true) {
            val c = peek()
            if (c == '\u0000') return Token(TokenType.EOF, "", line, pos - lineStart + 1, 0)
            if (c.isWhitespace()) { advance(); continue }
            if (c == '/' && peek(1) == '/') { while (peek() != '\n' && peek() != '\u0000') advance(); continue }
            break
        }
        val (startPos, startLine, startCol) = Triple(pos, line, pos - lineStart + 1)
        val c = peek()

        fun emit(type: TokenType, text: String? = null) = makeToken(type, text ?: src.substring(startPos, pos), startPos, startLine, startCol)

        if (c.isLetter() || c == '_') {
            val sb = StringBuilder()
            while (peek().isLetterOrDigit() || peek() == '_') sb.append(advance())
            return emit(when (sb.toString()) {
                "let" -> TokenType.LET
                "mut" -> TokenType.MUT
                "fn" -> TokenType.FN
                "struct" -> TokenType.STRUCT
                "import" -> TokenType.IMPORT
                "if" -> TokenType.IF
                "else" -> TokenType.ELSE
                "return" -> TokenType.RETURN
                "extern" -> TokenType.EXTERN
                "new" -> TokenType.NEW
                "void" -> TokenType.KW_VOID
                "i32" -> TokenType.KW_I32
                "bool" -> TokenType.KW_BOOL
                "string" -> TokenType.KW_STRING
                "true" -> TokenType.TRUE
                "false" -> TokenType.FALSE
                else -> TokenType.ID
            })
        }
        if (c.isDigit()) { while (peek().isDigit()) advance(); return emit(TokenType.INT_LIT) }
        if (c == '"') {
            advance()
            val sb = StringBuilder()
            while (peek() != '"' && peek() != '\u0000') {
                val ch = advance()
                if (ch == '\\') sb.append(when(advance()){ 'n'->'\n'; 't'->'\t'; 'r'->'\r'; '\\'->'\\'; '"'->'"'; else->'?' }) else sb.append(ch)
            }
            if (peek() != '"') error("Unterminated string")
            advance()
            return emit(TokenType.STRING_LIT, sb.toString())
        }
        advance()
        return when (c) {
            ':' -> emit(TokenType.COLON)
            '=' -> if (peek() == '=') { advance(); emit(TokenType.EQEQ) } else emit(TokenType.EQ)
            '+' -> emit(TokenType.PLUS)
            '-' -> if (peek() == '>') { advance(); emit(TokenType.ARROW) } else emit(TokenType.MINUS)
            '*' -> emit(TokenType.STAR)
            '/' -> emit(TokenType.SLASH)
            '%' -> emit(TokenType.PERCENT)
            '&' -> emit(TokenType.AMP)
            '|' -> emit(TokenType.PIPE)
            '^' -> emit(TokenType.CARET)
            '~' -> emit(TokenType.TILDE)
            '!' -> if (peek() == '=') { advance(); emit(TokenType.NEQ) } else error("Unexpected '!'")
            '<' -> if (peek() == '<') { advance(); emit(TokenType.LSHIFT) } else error("Unexpected '<'")
            '>' -> if (peek() == '>') { advance(); emit(TokenType.RSHIFT) } else error("Unexpected '>'")
            '(' -> emit(TokenType.LPAREN)
            ')' -> emit(TokenType.RPAREN)
            '{' -> emit(TokenType.LBRACE)
            '}' -> emit(TokenType.RBRACE)
            '.' -> emit(TokenType.DOT)
            ',' -> emit(TokenType.COMMA)
            else -> error("Char '$c'")
        }
    }
}