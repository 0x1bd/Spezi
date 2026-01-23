![Kotlin/Native](https://img.shields.io/badge/Kotlin/Native-Unsafe-blue?style=for-the-badge&logo=Kotlin)

[![Linux](https://img.shields.io/badge/Linux-FCC624?logo=linux&logoColor=black)](#)

# Spezi
Programming language 👍

## Setup (Fedora 43)
Install dependencies:

`dnf install llvm llvm-devel`

Install shared libs (runtime dependencies):

`dnf install libxcrypt-compat clang`

Build spezi:

`./gradlew linkReleaseExecutableNative`

## Example code

### Hello World
```spezi
extern fn printf(s: string) -> void

fn main() -> void {
    printf("Hello, World\n")
}
```