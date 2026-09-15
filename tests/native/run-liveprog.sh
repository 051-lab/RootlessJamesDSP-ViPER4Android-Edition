#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
build=build/native-liveprog
cmake -S tests/native -B "$build" -DLIVEPROG_ALIGNMENT_RECOVERY=OFF "$@"
cmake --build "$build" --parallel 2
"$build/liveprog_lifecycle"
printf 'LiveProg test binary: %s/liveprog_lifecycle\n' "$build"
