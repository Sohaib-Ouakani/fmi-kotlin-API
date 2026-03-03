#!/bin/bash
cd "$(dirname "$0")"
echo "Working dir: $(pwd)"
echo "Resources esiste: $(ls resources/binaries/darwin64/ 2>/dev/null || echo 'NO')"
./gradlew build
./build/bin/native/debugExecutable/KotlinNativeTemplate.kexe