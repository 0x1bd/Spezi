package spezi.domain

import spezi.common.SourceFile

enum class TokenType {
    EOF, ID, INT_LIT, FLOAT_LIT, STRING_LIT,

    LET, MUT, FN, STRUCT, IMPORT, IF, ELSE, RETURN, EXTERN, NEW, AS,

    KW_VOID, KW_BOOL, KW_STRING, KW_I32, KW_I64, KW_F32, KW_F64,

    TRUE, FALSE,

    COLON, EQ, EQEQ, NEQ,
    PLUS, MINUS, STAR, SLASH, PERCENT, BANG,
    AMP, PIPE, CARET, TILDE, LSHIFT, RSHIFT,
    LPAREN, RPAREN, LBRACE, RBRACE, DOT, COMMA, ARROW
}

data class Token(
    val type: TokenType,
    val value: String,
    val source: SourceFile,
    val line: Int,
    val col: Int,
    val length: Int
)