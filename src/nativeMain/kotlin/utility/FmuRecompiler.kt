package utility

import platform.posix.F_OK
import platform.posix.access

class FmuRecompiler() {
    private val exec = ProcessExecution()
    private val fs = FilesystemManager()
    private fun xmlAttr(xml: String, attr: String): String =
        Regex("""$attr="([^"]+)"""").find(xml)?.groupValues?.get(1) ?: ""

    private fun extractSources(xml: String): List<String> {
        val coSim = Regex("""<CoSimulation\b[^>]*>.*?</CoSimulation>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.value
            ?: error("Tag CoSimulation mancante: solo FMU CoSimulation supportati")

        return Regex("""<File\s+name="([^"]+)"""")
            .findAll(coSim)
            .map { it.groupValues[1] }
            .toList()
            .also { check(it.isNotEmpty()) { "Nessun file sorgente trovato in <SourceFiles>" } }
    }

    private fun discoverAliasFlags(nm: String, objs: List<String>, modelId: String, fmiPrefix: String): List<String> =
        objs.flatMap { obj ->
            exec.runWithOutput(nm, "--defined-only", obj)
                .lines()
                .mapNotNull { Regex("""[_]?(${modelId}_${fmiPrefix}[A-Za-z]+)""").find(it)?.groupValues?.get(1) }
                .toSet()
                .map { raw ->
                    val suffix = raw.removePrefix("${modelId}_")
                    "-Wl,-alias,_$raw,_$suffix"
                }
        }.distinct()

    fun synthesiseFmi2Headers(dir: String) {
        fs.writeFile("$dir/fmi2FunctionTypes.h", """
        #ifndef fmi2FunctionTypes_h
        #define fmi2FunctionTypes_h
        #include <stdlib.h>
        #include "fmi2TypesPlatform.h"
        typedef int fmi2Status;
        #define fmi2OK      0
        #define fmi2Warning 1
        #define fmi2Discard 2
        #define fmi2Error   3
        #define fmi2Fatal   4
        #define fmi2Pending 5
        typedef int fmi2Type;
        #define fmi2ModelExchange 0
        #define fmi2CoSimulation  1
        typedef int fmi2StatusKind;
        #define fmi2DoStepStatus       0
        #define fmi2PendingStatus      1
        #define fmi2LastSuccessfulTime 2
        #define fmi2Terminated         3
        typedef struct {
          fmi2Boolean newDiscreteStatesNeeded;
          fmi2Boolean terminateSimulation;
          fmi2Boolean nominalsOfContinuousStatesChanged;
          fmi2Boolean valuesOfContinuousStatesChanged;
          fmi2Boolean nextEventTimeDefined;
          fmi2Real    nextEventTime;
        } fmi2EventInfo;
        #endif
        """.trimIndent())

        fs.writeFile("$dir/fmi2Functions.h", """
        #ifndef fmi2Functions_h
        #define fmi2Functions_h
        #include <stdlib.h>
        #include "fmi2TypesPlatform.h"
        #include "fmi2FunctionTypes.h"
        #define fmi2Version "2.0"
        #ifndef FMI2_Export
          #if defined _WIN32 || defined __CYGWIN__
            #define FMI2_Export __declspec(dllexport)
          #else
            #define FMI2_Export __attribute__((visibility("default")))
          #endif
        #endif
        #endif
        """.trimIndent())

        fs.writeFile("$dir/fmi2TypesPlatform.h", """
        #ifndef fmi2TypesPlatform_h
        #define fmi2TypesPlatform_h
        #include <stddef.h>
        #define fmi2TypesPlatform "default"
        typedef double           fmi2Real;
        typedef int              fmi2Integer;
        typedef int              fmi2Boolean;
        typedef char             fmi2Char;
        typedef const fmi2Char*  fmi2String;
        typedef char             fmi2Byte;
        #define fmi2True  1
        #define fmi2False 0
        typedef void* fmi2Component;
        typedef void* fmi2ComponentEnvironment;
        typedef void* fmi2FMUstate;
        typedef unsigned int fmi2ValueReference;
        typedef void  (*fmi2CallbackLogger)(fmi2ComponentEnvironment,fmi2String,int,fmi2String,fmi2String,...);
        typedef void* (*fmi2CallbackAllocateMemory)(size_t,size_t);
        typedef void  (*fmi2CallbackFreeMemory)(void*);
        typedef void  (*fmi2StepFinished)(fmi2ComponentEnvironment,int);
        typedef struct {
          fmi2CallbackLogger         logger;
          fmi2CallbackAllocateMemory allocateMemory;
          fmi2CallbackFreeMemory     freeMemory;
          fmi2StepFinished           stepFinished;
          fmi2ComponentEnvironment   componentEnvironment;
        } fmi2CallbackFunctions;
        #endif
        """.trimIndent())
    }

    fun recompile(inputFmu: String, outputFmu: String) {
        val input = fs.pathAbsolute(inputFmu)
        val output = fs.pathAbsolute(outputFmu)
        check(fs.fileExists(input)) { "FMU non trovato: $input" }

        val tmp = exec.runWithOutput("mktemp", "-d", "/tmp/fmu_recompile_XXXXXX")
            .also { check(it.isNotBlank()) { "mktemp fallito" } }
        val extracted = "$tmp/extracted"
        exec.run("mkdir", "-p", extracted)


        try {
            check(exec.run("unzip", "-q", input, "-d", extracted) == 0) { "Estrazione fallita" }

            val xml      = fs.readFile("$extracted/modelDescription.xml")
            val modelId  = xmlAttr(xml, "modelIdentifier").also { check(it.isNotBlank())
            { "modelIdentifier non trovato" } }
            val fmiVer   = xmlAttr(xml, "fmiVersion").substringBefore('.').ifBlank { "2" }
            val fmiPrefix = "fmi$fmiVer"

            val sourcesDir = "$extracted/sources"
            check(access(sourcesDir, F_OK) == 0) { "Nessuna cartella sources/ — FMU solo binario" }
            synthesiseFmi2Headers(sourcesDir)

            val sources = extractSources(xml).map { "$sourcesDir/$it" }

            val targets = listOf("arm64-apple-macos11", "x86_64-apple-macos10.13")
            val objsByArch = targets.associateWith { target ->
                val arch   = target.substringBefore("-")
                val objDir = "$tmp/obj_$arch".also { exec.run("mkdir", "-p", it) }
                sources.map { src ->
                    val obj = "$objDir/${src.substringAfterLast('/').removeSuffix(".c")}.o"
                    check(exec.run(
                        "clang",
                        "--target=$target",
                        "-c",
                        "-fPIC",
                        "-O2",
                        "-I$sourcesDir",
                        src,
                        "-o",
                        obj
                    ) == 0) { "Compilazione fallita: $src [$arch]" }
                    obj
                }
            }

            // Alias dai simboli arm64 (sono gli stessi per entrambe le arch)
            val aliasFlags = discoverAliasFlags("nm", objsByArch.values.first(), modelId, fmiPrefix)

            val slices = targets.map { target ->
                val arch  = target.substringBefore("-")
                val objs  = objsByArch[target]!!
                val slice = "$tmp/$arch.dylib"
                check(exec.run(
                    "clang",
                    "--target=$target",
                    "-dynamiclib",
                    "-fPIC",
                    *aliasFlags.toTypedArray(),
                    *objs.toTypedArray(),
                    "-lm",
                    "-Wl,-undefined,dynamic_lookup",
                    "-o", slice
                ) == 0) { "Link fallito [$arch]" }
                slice
            }

            val universal = "$tmp/$modelId.dylib"
            check(exec.run("lipo", "-create", *slices.toTypedArray(), "-output", universal) == 0)
            { "lipo fallito" }

            val binDest = "$extracted/binaries/darwin64".also { exec.run("mkdir", "-p", it) }
            exec.run("cp", universal, "$binDest/$modelId.dylib")

            check(exec.run("cd", "\"$extracted\"", "&&", "zip", "-qr", "\"$output\"", ".") == 0)
            { "zip fallito" }

            println("Ricompilazione riuscita: $output")

        } finally {
            exec.run("rm", "-rf", tmp)
        }
    }
}