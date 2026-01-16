package spezi.test

import spezi.common.CompilationOptions
import spezi.common.Context
import spezi.common.SourceFile
import spezi.domain.TokenType
import spezi.frontend.Lexer
import spezi.frontend.Parser
import kotlin.test.*

class LexerParserTests {

    private fun createContext(content: String): Context {
        val opts = CompilationOptions(
            emptyList(), "out",
            keepIr = false,
            verbose = false,
            optimizationLevel = 0,
            libraries = emptyList(),
            includePaths = emptyList()
        )

        val ctx = Context(opts)
        ctx.currentSource = SourceFile("test.spz", content)
        return ctx
    }

    @Test
    fun `Lexer identifies tokens correctly`() {
        val code = "let x = 123"
        val lexer = Lexer(createContext(code))
        
        assertEquals(TokenType.LET, lexer.next().type)
        val id = lexer.next()
        assertEquals(TokenType.ID, id.type)
        assertEquals("x", id.value)
        assertEquals(TokenType.EQ, lexer.next().type)
        val num = lexer.next()
        assertEquals(TokenType.INT_LIT, num.type)
        assertEquals("123", num.value)
    }

    @Test
    fun `Parser handles struct definitions`() {
        val code = "struct Point { x: i32, y: i32 }"
        val parser = Parser(createContext(code))
        val program = parser.parseProgram()
        
        assertEquals(1, program.elements.size)
    }
}