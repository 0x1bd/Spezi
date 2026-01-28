package spezi.domain

import spezi.common.SourceFile

enum class TokenType {
    EOF, ID,

    // Literals
    INT_LIT, FLOAT_LIT, STRING_LIT, TRUE, FALSE,

    // Keywords
    LET, MUT, FN, STRUCT, IMPORT,
    IF, ELSE, RETURN, EXTERN, NEW, AS,

    // Types
    KW_VOID, KW_BOOL, KW_STRING,
    KW_I32, KW_I64, KW_F32, KW_F64,

    // Symbols
    COLON, EQ, ARROW, DOT, COMMA,
    LPAREN, RPAREN, LBRACE, RBRACE,

    // Operations
    EQEQ, NEQ,
    LESS, LESS_EQ, GREATER, GREATER_EQ,
    PLUS, MINUS, STAR, SLASH, PERCENT,

    // Bitwise
    AMP, PIPE, CARET, TILDE,
    LSHIFT, RSHIFT,

    // Unary
    BANG
}

data class Token(
    val type: TokenType,
    val value: String,
    val source: SourceFile,
    val line: Int,
    val col: Int,
    val length: Int
)