#pragma once
#include "include/cef_app.h"
#include "include/cef_client.h"
namespace musxi::cef_adapter {
CefRefPtr<CefApp> makeApp();
CefRefPtr<CefClient> makeClient(const std::string& trustedUrl, void* nativeWindow, bool smokeTest, void* testWindow=nullptr);
bool closeBrowsers();
bool smokePassed();
bool browserReadyToClose();
void retryBrowser();
inline constexpr unsigned RecoveryFailed=0x8000+42;
inline constexpr wchar_t DragRegionProperty[]=L"MusxiDragRegion";
}
