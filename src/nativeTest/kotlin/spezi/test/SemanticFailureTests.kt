package spezi.test

import kotlin.test.*

class SemanticFailureTests {

    @BeforeTest
    fun setup() = TestUtils.setup()

    @AfterTest
    fun tearDown() = TestUtils.cleanup()

    @Test
    fun `Fail on undefined variable`() {
        val code = """
            fn main() -> i32 {
                return unknown_var
            }
        """
        TestUtils.assertCompilationFails(code)
    }

    @Test
    fun `Fail on type mismatch in assignment`() {
        val code = """
            fn main() -> i32 {
                let x: i32 = 10
                x = true
                return 0
            }
        """
        TestUtils.assertCompilationFails(code)
    }

    @Test
    fun `Fail on undefined struct in new`() {
        val code = """
            fn main() -> i32 {
                let v = new UnknownStruct()
                return 0
            }
        """
        TestUtils.assertCompilationFails(code)
    }
}