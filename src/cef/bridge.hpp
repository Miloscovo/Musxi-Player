#pragma once
#include "include/cef_app.h"
#include "include/cef_client.h"
namespace musxi::cef_adapter {
CefRefPtr<CefApp> makeApp();
CefRefPtr<CefClient> makeClient(const std::string& trustedUrl, void* nativeWindow, bool smokeTest);
bool closeBrowsers();
bool smokePassed();
}
