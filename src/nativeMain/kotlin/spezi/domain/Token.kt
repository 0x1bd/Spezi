package spezi.domain

enum class TokenType {
    EOF, ID, INT_LIT, STRING_LIT,

    // Keywords
    LET, MUT, FN, STRUCT, IMPORT, IF, ELSE, RETURN, EXTERN, NEW,

    // Types & Bools
    KW_VOID, KW_I32, KW_BOOL, KW_STRING, TRUE, FALSE,

    // Symbols
    COLON, EQ, EQEQ, NEQ,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    AMP, PIPE, CARET, TILDE, LSHIFT, RSHIFT,
    LPAREN, RPAREN, LBRACE, RBRACE, DOT, COMMA, ARROW
}

data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val col: Int,
    val length: Int
)