import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import libfmi.*
import model.fmu.Fmu
import platform.posix.`false`
import platform.posix.getcwd
import platform.posix.`true`
import utility.FmuRecompiler
import kotlin.math.round


const val FMU_PATH = "./resources/result.fmu"

@OptIn(ExperimentalForeignApi::class)
fun getWorkingDir(): String {
    val buffer = ByteArray(4096)
    return buffer.usePinned {
        getcwd(it.addressOf(0), 4096.toULong())?.toKString() ?: "."
    }
}


@OptIn(ExperimentalForeignApi::class)
fun main() {
    memScoped {
//        recompileFMU("./resources/BouncingBall.fmu", FMU_PATH)
        val recompiler = FmuRecompiler()
        recompiler.recompile("./resources/BouncingBall.fmu", FMU_PATH)
        val workDir = getWorkingDir()
        val resources = "$workDir/resources"

        val context: CPointer<fmi_import_context_t>? = fmi_import_allocate_context(null)
        val version = fmi_import_get_fmi_version(
            context,
            FMU_PATH,
            resources
        )
        if (version == fmi_version_2_0_enu) {
            println("yeeeeeee")
        } else {
            throw IllegalArgumentException("fmi version $version is not supported. expected version: FMI 2.0")
        }
        val characteristics: MutableMap<String, String?> = mutableMapOf()

        val fmi = fmi2_import_parse_xml(context, resources, null)

        characteristics["Model Name"] = fmi2_import_get_model_name(fmi)?.toKString()
        characteristics["Description"] = fmi2_import_get_description(fmi)?.toKString()
        characteristics["Author"] = fmi2_import_get_author(fmi)?.toKString()
        characteristics["FMU Version"] = fmi2_import_get_model_version(fmi)?.toKString()
        characteristics["Default Experiment Start"] = fmi2_import_get_default_experiment_start(fmi).toString()
        characteristics["Default Experiment Stop"] = fmi2_import_get_default_experiment_stop(fmi).toString()
        characteristics["Default Experiment Step"] = fmi2_import_get_default_experiment_step(fmi).toString()
        characteristics["FMU kind"] = fmi2_import_get_fmu_kind(fmi).toString()

        println(characteristics)

        val varlist = fmi2_import_get_variable_list(fmi, 0)
        val varlist_size = fmi2_import_get_variable_list_size(varlist)

        println("--- Variable Names ---")
        for (i in 0 until varlist_size.toInt()) {
            val variable = fmi2_import_get_variable(varlist, i.toULong())
            val name = fmi2_import_get_variable_name(variable)?.toKString()
            println(name)
        }

        //inzio set-up esperimento
        val fmuKind = fmi2_import_get_fmu_kind(fmi)
        println("Kind: $fmuKind")

        //caricamento DLL
        val dllResult = fmi2_import_create_dllfmu(
            fmi,
            fmi_version_2_0_enu,
            null
        )
        val errMsg = fmi2_import_get_last_error(fmi)?.toKString()
        println("Last error dopo create_dllfmu: $errMsg")
        check(dllResult == 0) {
            "Errore nel caricamento della DLL (status=$dllResult), msg=$errMsg"
        }

        //instanziamento
        val instance = fmi2_import_instantiate(fmi, "experiment1", fmi2_type_t.fmi2_cosimulation, null, `false`)
        check(instance == 0) {
            "Errore istanziamento: ${fmi2_import_get_last_error(fmi)?.toKString()}"
        }

        //set-up valori esperimento
        if (fmi2_import_setup_experiment(
            fmi,
            `false`,
            0.0,
            0.0,
            `false`,
            0.0
        ) != fmi2_status_t.fmi2_status_ok)
        {
            throw IllegalStateException("Error while setting up the experiment: ${fmi2_import_get_last_error(fmi)?.toKString()}")
        } else {
            println("Experiment successfully setup")
        }

        fmi2_import_enter_initialization_mode(fmi)
        fmi2_import_exit_initialization_mode(fmi)

        val altezza: CPointer<fmi2_import_variable_t> = fmi2_import_get_variable_by_name(fmi, "h")
            ?: error("error")
        val vrAltezza: fmi2_value_reference_t = fmi2_import_get_variable_vr(altezza)

    val vrArray = allocArray<fmi2_value_reference_tVar>(1)
    vrArray[0] = vrAltezza

    val valueArray = allocArray<DoubleVar>(1)

    var time = 0.0
    val step = 0.01

    fun Double.roundTo2(): Double {
        return round(this * 100) / 100
    }

    println("---- Simulation Start ----")

    while (time < 3.0) {

        fmi2_import_do_step(

            fmi,
            time,
            step,
            `true`
        )

        fmi2_import_get_real(
            fmi,
            vrArray,
            1.toULong(),
            valueArray
        )

        val height = valueArray[0]

        println("t=${time.roundTo2()}  h=${height.roundTo2()}")

        time += step
    }

        println("---- Simulation End ----")

        fmi2_import_terminate(fmi)
        fmi2_import_free_instance(fmi)
        fmi2_import_destroy_dllfmu(fmi)
        fmi2_import_free(fmi)
        fmi_import_free_context(context)
    }
}
