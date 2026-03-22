package utility

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getcwd

class FilesystemManager {
    val bufferSize = 4096

    @OptIn(ExperimentalForeignApi::class)
    fun readFile(fileName: String): String {
        check(fileExists(fileName)) { "File does not exist: $fileName" }
        val file = fopen(fileName, "r") ?: return ""
        val sb = StringBuilder()

        memScoped {
            val buff = allocArray<ByteVar>(bufferSize)
            while (fgets(buff, bufferSize, file) != null) {
                sb.append(buff.toKString())
            }
        }
        fclose(file)

        return sb.toString().trim()
    }

    @OptIn(ExperimentalForeignApi::class)
    fun writeFile(fileName: String, content: String) {
        val file = fopen(fileName, "w") ?: return
        fputs(content, file)
        fclose(file)
    }

    @OptIn(ExperimentalForeignApi::class)
    fun pathAbsolute(path: String): String {
        if (path.startsWith("/")) return path
        return memScoped {
            val buf = allocArray<ByteVar>(4096)
            val cwd = getcwd(buf, 4096u)?.toKString() ?: "."
            "$cwd/$path"
        }
    }

    fun fileExists(name: String): Boolean {
        return access(name, F_OK) == 0
    }
}