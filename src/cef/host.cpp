#include "include/cef_app.h"

namespace musxi::cef_adapter {
// The eventual executable must call this before creating native application state.
int executeSubprocess(HINSTANCE instance, CefRefPtr<CefApp> app, void* sandboxInfo) {
    return CefExecuteProcess(CefMainArgs(instance), app, sandboxInfo);
}
}
