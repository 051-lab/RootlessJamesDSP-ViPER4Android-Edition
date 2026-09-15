#include <assert.h>
#include "numericSys/FFTConvolver.h"
#include "numericSys/FilterDesign/cos_fib_paraunitary.c"
double getOptimalDF(unsigned int N, unsigned int m);

int main(void)
{
    /* Compare partitioned stereo convolution and IR refresh to direct convolution. */
    float ir[19], input[128], left[128], right[128];
    for (int i = 0; i < 19; ++i) ir[i] = (i % 3 - 1) * 0.03f;
    for (int i = 0; i < 128; ++i) input[i] = sinf(i * 0.17f);
    FFTConvolver2x2 source, target;
    FFTConvolver2x2Init(&source);
    FFTConvolver2x2Init(&target);
    assert(FFTConvolver2x2LoadImpulseResponse(&source, 8, ir, ir, 19));
    assert(FFTConvolver2x2LoadImpulseResponse(&target, 8, ir, ir, 19));
    for (int i = 0; i < 19; ++i) ir[i] *= 2;
    FFTConvolver2x2RefreshImpulseResponse(&source, &target, ir, ir, 19);
    for (int pos = 0; pos < 128;) {
        unsigned int n = min(7, 128 - pos);
        FFTConvolver2x2Process(&target, input + pos, input + pos, left + pos, right + pos, n);
        pos += n;
    }
    for (int i = 0; i < 128; ++i) {
        double expected = 0;
        for (int j = 0; j < 19 && j <= i; ++j) expected += ir[j] * input[i-j];
        assert(fabs(left[i] - expected) < 1e-5);
        assert(fabs(right[i] - expected) < 1e-5);
    }
    FFTConvolver2x2Free(&source);
    FFTConvolver2x2Free(&target);

    /* Negative filter-design coefficients must survive unsigned-char ARM defaults. */
    int8_t c[32 * 32];
    genC(4, 32, c);
    int negative = 0;
    for (int i = 0; i < 32 * 32; ++i) negative += c[i] < 0;
    assert(negative > 0);
    constraintsSparseMtx sparse;
    eqconPrecompute(32, c, &sparse);
    assert(sparse.numCon > 0 && sparse.mOpt > 0);
    for (unsigned int i = 0; i < sparse.numCon * sparse.constraintMatSize; ++i) {
        assert(sparse.positionQ[i] >= -1 && sparse.positionQ[i] < 16);
        assert(sparse.positionQQ[i] >= -1 && sparse.positionQQ[i] < 16);
        assert(sparse.correspondingValueQ[i] <= 4);
        assert(sparse.correspondingValueQQ[i] <= 4);
    }
    free(sparse.positionQ);
    free(sparse.positionQQ);
    free(sparse.correspondingValueQ);
    free(sparse.correspondingValueQQ);
    for (unsigned int n = 1; n <= 160; ++n)
        for (unsigned int m = 1; m <= 8; ++m)
            assert(isfinite(getOptimalDF(n, m)) && getOptimalDF(n, m) > 0);
    puts("Convolution/reference, IR refresh, signed coefficients and filter-width checks passed");
    return 0;
}
