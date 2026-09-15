#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
eel=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/eel2
build=$(mktemp -d /tmp/rjdsp-native.XXXXXX)
# Keep binaries for inspection. No Android/JNI behavior is simulated here.
for test in wav_bounds string_decode; do
    gcc -O1 -g -ffunction-sections -fdata-sections \
        -fsanitize=address,undefined --param asan-globals=0 \
        -Wno-incompatible-pointer-types -Wno-implicit-int -Wno-discarded-qualifiers \
        -iquote "$eel" "tests/native/${test}_test.c" "$eel/s_str.c" \
        -Wl,--gc-sections -lm -lpthread -o "$build/$test"
    "$build/$test"
done
gcc -O1 -g -mlong-double-64 -ffunction-sections -fdata-sections \
    -Wno-incompatible-pointer-types -Wno-implicit-int -Wno-discarded-qualifiers \
    -iquote "$eel" tests/native/wav_bounds_test.c "$eel/s_str.c" \
    -Wl,--gc-sections -lm -lpthread -o "$build/wav-ld64"
"$build/wav-ld64"
for char_mode in signed unsigned; do
    gcc -O1 -g -f${char_mode}-char -ffunction-sections -fdata-sections \
        -fsanitize=address,undefined --param asan-globals=0 -iquote "$eel" \
        tests/native/dsp_test.c "$eel/numericSys/FFTConvolver.c" \
        "$eel/numericSys/codelet.c" "$eel/numericSys/FilterDesign/polyphaseFilterbank.c" \
        "$eel/numericSys/FilterDesign/generalFdesign.c" \
        -Wl,--gc-sections -lm -o "$build/dsp-$char_mode"
    "$build/dsp-$char_mode"
done
printf 'Native test binaries: %s\n' "$build"
gcc -O1 -g -fsanitize=address,undefined -iquote "$eel" \
    tests/native/wav_io_test.c -lm -o "$build/wav-io"
"$build/wav-io"
