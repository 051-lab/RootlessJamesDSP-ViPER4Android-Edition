#include "EELStdOutExtension.h"
#include "Effects/eel2/ns-eel.h"
#include <pthread.h>

static stdOutHandler _stdOutHandlerPtr = NULL;
static void* _stdOutHandlerUserPtr = NULL;
static pthread_mutex_t _stdOutHandlerMutex = PTHREAD_MUTEX_INITIALIZER;

void writeCircularStringBuf(char *cmdCur)
{
    pthread_mutex_lock(&_stdOutHandlerMutex);
    stdOutHandler handler = _stdOutHandlerPtr;
    void* userData = _stdOutHandlerUserPtr;
    if (handler != NULL)
        handler(cmdCur, userData);
    pthread_mutex_unlock(&_stdOutHandlerMutex);
}

void setStdOutHandler(stdOutHandler funcPtr, void* userData)
{
    pthread_mutex_lock(&_stdOutHandlerMutex);
    _stdOutHandlerPtr = funcPtr;
    _stdOutHandlerUserPtr = userData;
    pthread_mutex_unlock(&_stdOutHandlerMutex);
}

int isStdOutHandlerSet()
{
    pthread_mutex_lock(&_stdOutHandlerMutex);
    const int set = _stdOutHandlerPtr != NULL;
    pthread_mutex_unlock(&_stdOutHandlerMutex);
    return set;
}
