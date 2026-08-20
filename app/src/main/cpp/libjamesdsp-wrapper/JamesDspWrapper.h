#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>

typedef struct
{
    void* dsp;
    JavaVM* vm;
    jobject callbackInterface;
    jmethodID callbackOnLiveprogOutput;
    jmethodID callbackOnLiveprogExec;
    jmethodID callbackOnLiveprogResult;
    jmethodID callbackOnVdcParseError;
} JamesDspWrapper;

/* C interop function */
static void receiveLiveprogStdOut(const char* buffer, void* userData);

#endif // DSPHOST_H
