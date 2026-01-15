package spezi.common

import okio.FileSystem
import okio.Path.Companion.toPath

data class SourceFile(
    val path: String,
    val content: String
) {

    val lines: List<String> by lazy { content.lines() }

    companion object {

        fun fromPath(pathString: String): SourceFile {
            val fs = FileSystem.SYSTEM
            val path = pathString.toPath()

            if (!fs.exists(path)) {
                throw Exception("File not found: $pathString")
            }

            val content = fs.read(path) {
                readUtf8()
            }

            return SourceFile(pathString, content)
        }
    }

}