package spezi.test

import kotlin.test.*

class IntegrationTests {

    @BeforeTest
    fun setup() {
        TestUtils.setup()
    }

    @AfterTest
    fun tearDown() {
        TestUtils.cleanup()
    }

    @Test
    fun `Basic Arithmetic and Printing`() {
        val code = """
            extern fn printf(f: string, v: i32) -> void
            fn main() -> i32 {
                let res = 10 + 5 * 2
                printf("%d", res)
                return 0
            }
        """
        val output = TestUtils.compileAndRun(code)
        assertEquals("20", output)
    }

    @Test
    fun `Structs and Extension Methods`() {
        val code = """
            extern fn printf(f: string, v: i32) -> void
            struct Vector { x: i32, y: i32 }
            
            fn Vector.magSq() -> i32 {
                return (self.x * self.x) + (self.y * self.y)
            }
            
            fn main() -> i32 {
                let v = new Vector(3, 4)
                printf("%d", v.magSq())
                return 0
            }
        """
        val output = TestUtils.compileAndRun(code)
        assertEquals("25", output)
    }

    @Test
    fun `Logic and Control Flow`() {
        val code = """
            extern fn printf(f: string, v: i32) -> void
            fn main() -> i32 {
                if 10 == 10 {
                    printf("T", 0)
                }
                if 5 != 5 {
                    printf("F", 0)
                } else {
                    printf("E", 0)
                }
                return 0
            }
        """
        val output = TestUtils.compileAndRun(code)
        assertEquals("TE", output)
    }

    @Test
    fun `Recursive Function`() {
        val code = """
            extern fn printf(f: string, v: i32) -> void
            fn fib(n: i32) -> i32 {
                if n == 0 { return 0 }
                if n == 1 { return 1 }
                return fib(n - 1) + fib(n - 2)
            }
            fn main() -> i32 {
                printf("%d", fib(6))
                return 0
            }
        """
        val output = TestUtils.compileAndRun(code)
        assertEquals("8", output)
    }

    @Test
    fun `Import Module`() {
        val mathLib = """
            fn square(n: i32) -> i32 { return n * n }
        """
        
        val mainCode = """
            extern fn printf(f: string, v: i32) -> void
            import lib.math
            fn main() -> i32 {
                printf("%d", square(5))
                return 0
            }
        """
        
        val output = TestUtils.compileAndRun(
            mainCode, 
            extraFiles = mapOf("lib/math.spz" to mathLib)
        )
        assertEquals("25", output)
    }
}