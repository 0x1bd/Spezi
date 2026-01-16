package spezi.domain

sealed interface AstNode {

    val loc: Token
}

data class Program(val elements: List<AstNode>, override val loc: Token) : AstNode

data class StructDef(
    val name: String,
    val fields: List<Pair<String, Type>>,
    override val loc: Token
) : AstNode

data class FnDef(
    val name: String,
    val extensionOf: Type?,
    val args: List<Pair<String, Type>>,
    val retType: Type,
    val body: Block,
    override val loc: Token
) : AstNode

data class ExternFnDef(
    val name: String,
    val args: List<Pair<String, Type>>,
    val retType: Type,
    override val loc: Token
) : AstNode

data class Block(
    val stmts: List<AstNode>,
    val declaredVars: MutableList<VarDecl> = mutableListOf(),
    override val loc: Token
) : AstNode

data class VarDecl(
    val name: String,
    val type: Type?,
    val isMut: Boolean,
    val init: Expr?,
    override val loc: Token
) : AstNode

data class Assign(val name: String, val value: Expr, override val loc: Token) : AstNode

data class IfStmt(
    val cond: Expr,
    val thenBlock: Block,
    val elseBlock: Block?,
    override val loc: Token
) : AstNode

data class ReturnStmt(val value: Expr?, override val loc: Token) : AstNode

sealed interface Expr : AstNode {

    var resolvedType: Type
}

data class LiteralInt(val value: Long, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.I32
}

data class LiteralFloat(val value: Double, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.F64
}

data class LiteralBool(val value: Boolean, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.Bool
}

data class LiteralString(val value: String, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.String
}

data class VarRef(val name: String, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.Unknown
}

data class BinOp(val left: Expr, val op: TokenType, val right: Expr, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.Unknown
}

data class UnaryOp(val op: TokenType, val operand: Expr, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.Unknown
}

data class CastExpr(val expr: Expr, val targetType: Type, override val loc: Token) : Expr {

    override var resolvedType: Type = targetType
}

data class Call(val name: String, val args: List<Expr>, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.Unknown
}

data class ConstructorCall(val typeName: String, val args: List<Expr>, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.Unknown
}

data class Access(val objectExpr: Expr, val member: String, override val loc: Token) : Expr {

    override var resolvedType: Type = Type.Unknown
}