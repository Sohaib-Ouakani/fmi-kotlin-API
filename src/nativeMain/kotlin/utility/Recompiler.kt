package utility

import kotlinx.cinterop.*
import platform.posix.*

// ── Process execution ─────────────────────────────────────────────────────────

/** Runs a command, returns exit code. Stdout/stderr inherit from parent. */
fun run(vararg args: String): Int {
    val cmd = args.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
    return system(cmd)
}

/** Runs a command and captures stdout as a trimmed string. */
@OptIn(ExperimentalForeignApi::class)
fun capture(vararg args: String): String {
    val cmd = args.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
    val pipe = popen(cmd, "r") ?: return ""
    val sb = StringBuilder()
    memScoped {
        val buf = allocArray<ByteVar>(1024)
        while (fgets(buf, 1024, pipe) != null) sb.append(buf.toKString())
    }
    pclose(pipe)
    return sb.toString().trim()
}

// ── Pure-Kotlin path helpers (no subprocesses) ────────────────────────────────

/** Returns the directory portion of a path. "/a/b/c.txt" → "/a/b" */
fun pathDirname(path: String): String {
    val trimmed = path.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    return when {
        idx < 0  -> "."
        idx == 0 -> "/"
        else     -> trimmed.substring(0, idx)
    }
}

/** Returns the filename portion of a path. "/a/b/c.txt" → "c.txt" */
fun pathBasename(path: String): String =
    path.trimEnd('/').substringAfterLast('/')

/**
 * Resolves a path to absolute form without spawning any subprocess.
 * If already absolute, returns as-is. If relative, prepends cwd.
 */
@OptIn(ExperimentalForeignApi::class)
fun pathAbsolute(path: String): String {
    if (path.startsWith("/")) return path
    return memScoped {
        val buf = allocArray<ByteVar>(4096)
        val cwd = getcwd(buf, 4096u)?.toKString() ?: "."
        "$cwd/$path"
    }
}

// ── File system helpers ───────────────────────────────────────────────────────

fun mkdirP(path: String) { run("/bin/mkdir", "-p", path) }

@OptIn(ExperimentalForeignApi::class)
fun readFile(path: String): String {
    val f = fopen(path, "r") ?: return ""
    val sb = StringBuilder()
    memScoped {
        val buf = allocArray<ByteVar>(4096)
        while (fgets(buf, 4096, f) != null) sb.append(buf.toKString())
    }
    fclose(f)
    return sb.toString()
}

@OptIn(ExperimentalForeignApi::class)
fun writeFile(path: String, content: String) {
    val f = fopen(path, "w") ?: error("Cannot write: $path")
    fputs(content, f)
    fclose(f)
}

/** Writes [content] to [path] only if the file does not already exist. */
fun writeIfMissing(path: String, content: String) {
    if (access(path, F_OK) == 0) return  // already exists — preserve it
    writeFile(path, content)
}

/** Returns all files under [dir] matching [ext] (e.g. ".c", ".h"). */
fun findFiles(dir: String, ext: String): List<String> =
    capture("/usr/bin/find", dir, "-name", "*$ext").lines().filter { it.isNotBlank() }

// ── XML helpers ───────────────────────────────────────────────────────────────

fun xmlAttr(xml: String, attr: String): String =
    Regex("""$attr="([^"]+)"""").find(xml)?.groupValues?.get(1) ?: ""

// ── FMI header synthesis ──────────────────────────────────────────────────────

/** Writes FMI 2 headers into [dir], skipping any that already exist. */
fun synthesiseFmi2Headers(dir: String) {
    writeIfMissing("$dir/fmi2TypesPlatform.h", """
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

    writeIfMissing("$dir/fmi2FunctionTypes.h", """
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

    writeIfMissing("$dir/fmi2Functions.h", """
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
}

/** Writes FMI 3 headers into [dir], skipping any that already exist. */
fun synthesiseFmi3Headers(dir: String) {
    writeIfMissing("$dir/fmi3TypesPlatform.h", """
#ifndef fmi3TypesPlatform_h
#define fmi3TypesPlatform_h
#include <stdint.h>
#include <stddef.h>
typedef double   fmi3Float64;
typedef float    fmi3Float32;
typedef int64_t  fmi3Int64;
typedef int32_t  fmi3Int32;
typedef int16_t  fmi3Int16;
typedef int8_t   fmi3Int8;
typedef uint64_t fmi3UInt64;
typedef uint32_t fmi3UInt32;
typedef uint16_t fmi3UInt16;
typedef uint8_t  fmi3UInt8;
typedef int      fmi3Boolean;
typedef char     fmi3Char;
typedef const fmi3Char* fmi3String;
typedef uint8_t  fmi3Byte;
typedef uint32_t fmi3ValueReference;
typedef void*    fmi3Instance;
typedef void*    fmi3InstanceEnvironment;
typedef void*    fmi3FMUState;
#define fmi3True  1
#define fmi3False 0
#endif
""".trimIndent())

    writeIfMissing("$dir/fmi3Functions.h", """
#ifndef fmi3Functions_h
#define fmi3Functions_h
#include <stdlib.h>
#include "fmi3TypesPlatform.h"
#define fmi3Version "3.0"
typedef int fmi3Status;
#define fmi3OK      0
#define fmi3Warning 1
#define fmi3Discard 2
#define fmi3Error   3
#define fmi3Fatal   4
typedef int fmi3Type;
#define fmi3ModelExchange      0
#define fmi3CoSimulation       1
#define fmi3ScheduledExecution 2
#ifndef FMI3_Export
  #if defined _WIN32 || defined __CYGWIN__
    #define FMI3_Export __declspec(dllexport)
  #else
    #define FMI3_Export __attribute__((visibility("default")))
  #endif
#endif
#endif
""".trimIndent())
}

// ── Core function ─────────────────────────────────────────────────────────────

fun recompileFmu(inputFmu: String, outputFmu: String) {

    // ── Resolve paths (pure Kotlin, zero subprocesses) ────────────────────────
    val input  = pathAbsolute(inputFmu)
    val outDir = pathAbsolute(pathDirname(outputFmu))
    val output = "$outDir/${pathBasename(outputFmu)}"
    mkdirP(outDir)

    check(access(input, F_OK) == 0) { "Input FMU not found: $input" }

    // ── Toolchain detection (pure access() calls, zero subprocesses) ──────────
    fun toolExists(path: String): Boolean =
        access(path, F_OK) == 0 && access(path, X_OK) == 0

    fun findTool(vararg candidates: String): String? =
        candidates.firstOrNull { toolExists(it) }

    val cc = findTool(
        "/opt/homebrew/opt/llvm/bin/clang",
        "/usr/local/opt/llvm/bin/clang",
        "/usr/bin/clang",
        "/Library/Developer/CommandLineTools/usr/bin/clang",
        "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"
    ) ?: error("clang not found. Run: xcode-select --install")

    val nm = findTool(
        "/opt/homebrew/opt/llvm/bin/llvm-nm",
        "/usr/local/opt/llvm/bin/llvm-nm",
        "/usr/bin/nm",
        "/Library/Developer/CommandLineTools/usr/bin/nm",
        "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"
    ) ?: error("nm not found. Run: xcode-select --install")

    val lipo = findTool(
        "/usr/bin/lipo",
        "/Library/Developer/CommandLineTools/usr/bin/lipo",
        "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"
    ) ?: error("lipo not found. Run: xcode-select --install")

    val unzip = findTool("/usr/bin/unzip") ?: error("unzip not found")
    val zip   = findTool("/usr/bin/zip")   ?: error("zip not found")

    // ── Working directory ─────────────────────────────────────────────────────
    val tmp = capture("/usr/bin/mktemp", "-d", "/tmp/fmu_recompile_XXXXXX")
    check(tmp.isNotBlank()) { "mktemp failed — could not create temp directory" }
    val work = "$tmp/extracted"
    mkdirP(work)

    try {
        // ── Extract FMU ───────────────────────────────────────────────────────
        check(run(unzip, "-q", input, "-d", work) == 0) { "Failed to unzip: $input" }

        // ── Parse modelDescription.xml ────────────────────────────────────────
        val modelDesc = "$work/modelDescription.xml"
        println(modelDesc)
        check(access(modelDesc, F_OK) == 0) { "modelDescription.xml not found in FMU" }

        val xml        = readFile(modelDesc)
        val modelId    = xmlAttr(xml, "modelIdentifier")
        check(modelId.isNotBlank()) { "Could not extract modelIdentifier" }

        val fmiVersion = xmlAttr(xml, "fmiVersion").substringBefore('.').ifBlank { "2" }
        val fmiPrefix  = "fmi$fmiVersion"

        // ── Locate C sources ──────────────────────────────────────────────────
        val sourcesDir = "$work/sources"
        check(access(sourcesDir, F_OK) == 0) {
            "No sources/ directory — binary-only FMUs cannot be recompiled"
        }

        val unityCandidates = listOf("$sourcesDir/all.c", "$sourcesDir/All.c")
        val cSources: List<String> = unityCandidates.firstOrNull { access(it, F_OK) == 0 }
            ?.let { listOf(it) }
            ?: findFiles(sourcesDir, ".c")

        check(cSources.isNotEmpty()) { "No C sources found in FMU (exit 3)" }

        // ── Synthesise FMI headers into sourcesDir ────────────────────────────
        // writeIfMissing preserves any headers the FMU already ships;
        // only fills in whichever ones are genuinely absent.
        synthesiseFmi2Headers(sourcesDir)
        synthesiseFmi3Headers(sourcesDir)

        // ── Collect include paths ─────────────────────────────────────────────
        val includeDirs = findFiles(work, ".h")
            .map { pathDirname(it) }
            .toMutableSet()

        // Always ensure sourcesDir is on the include path
        includeDirs += sourcesDir

        val includeFlags = includeDirs.joinToString(" ") { "-I$it" }

        // ── Compile for both architectures ────────────────────────────────────
        fun compileArch(arch: String): List<String> {
            val objDir = "$tmp/obj_$arch".also { mkdirP(it) }
            return cSources.map { src ->
                val obj = "$objDir/${pathBasename(src).removeSuffix(".c")}.o"
                val cmd = "$cc --target=$arch-apple-macos11 -c -fPIC -O2 " +
                        "-DFMI_VERSION=$fmiVersion $includeFlags \"$src\" -o \"$obj\""
                check(system(cmd) == 0) { "Compilation failed: $src [$arch]" }
                obj
            }
        }

        val objsArm64 = compileArch("arm64")
        val objsX86   = compileArch("x86_64")

        // ── Discover FMI symbol aliases ───────────────────────────────────────
        val aliasFlags = objsArm64.flatMap { obj ->
            capture(nm, "--defined-only", obj)
                .lines()
                .mapNotNull { Regex("""[_]?(${modelId}_${fmiPrefix}[A-Za-z]+)""").find(it)?.groupValues?.get(1) }
                .toSet()
                .map { raw ->
                    val suffix = raw.removePrefix("${modelId}_")
                    "-Wl,-alias,_$raw,_$suffix"
                }
        }.distinct()

        // ── Link thin dylibs ──────────────────────────────────────────────────
        fun linkArch(arch: String, objs: List<String>): String {
            val lib = "$tmp/${modelId}_$arch.dylib"
            val aliases = aliasFlags.joinToString(" ")
            val objList = objs.joinToString(" ") { "\"$it\"" }
            val cmd = "$cc --target=$arch-apple-macos11 -dynamiclib -fPIC " +
                    "$aliases $objList -lm -Wl,-undefined,dynamic_lookup -o \"$lib\""
            check(system(cmd) == 0) { "Linking failed [$arch]" }
            return lib
        }

        val libArm64 = linkArch("arm64",  objsArm64)
        val libX86   = linkArch("x86_64", objsX86)

        // ── Merge into universal binary ───────────────────────────────────────
        val libUniversal = "$tmp/${modelId}.dylib"
        check(run(lipo, "-create", libArm64, libX86, "-output", libUniversal) == 0) {
            "lipo failed"
        }

        // ── Place binary into FMU structure ───────────────────────────────────
        val binDest = "$work/binaries/darwin64"
        mkdirP(binDest)
        run("/bin/cp", libUniversal, "$binDest/${modelId}.dylib")

        // ── Repackage FMU ─────────────────────────────────────────────────────
        mkdirP(pathDirname(output))
        check(system("cd \"$work\" && $zip -qr \"$output\" .") == 0) { "zip failed" }

    } finally {
        run("/bin/rm", "-rf", tmp)
    }
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun recompileFMU(input: String, output: String) {
    require(input.isNotBlank() && output.isNotBlank()) { "Usage: fmu_recompile <input.fmu> <output.fmu>" }
    recompileFmu(input, output)
}