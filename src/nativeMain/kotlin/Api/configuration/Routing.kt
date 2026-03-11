package Api.configuration

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import model.fmu.Fmu

const val FMU_PATH = "./resources/BouncingBall.fmu"

@OptIn(ExperimentalForeignApi::class)
fun getResourcesPath(): String {
    val buffer = ByteArray(4096)
    val cwd = buffer.usePinned {
        platform.posix.getcwd(it.addressOf(0), 4096.toULong())?.toKString() ?: "."
    }
    return "$cwd/resources"
}

fun Application.configureRouting() {
    var fmu: Fmu? = null
    routing {
        get("/health") {
            call.respondText("OK")
        }

        get("/") {
            call.respondText("Welcome to the home of the api")
        }

        route("/fmi") {
            get("/init") {
                val resources = getResourcesPath()

                fmu = Fmu(FMU_PATH, resources)
                call.respondText("to view info about the fmu type /fmi/info")
            }

            get("/info") {
                if (fmu == null) {
                    return@get call.respondText(
                        "please initiate the fmu by making a get request to /init with fmu path and path to unpack it"
                    )
                }
                val result = fmu.fmuInfo
                fmu.close()

                call.respond(result)
            }
        }
    }
}