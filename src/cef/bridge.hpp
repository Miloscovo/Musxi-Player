#pragma once
#include "include/cef_app.h"
#include "include/cef_client.h"
#include "include/cef_browser.h"
namespace musxi::cef_adapter {
CefRefPtr<CefApp> makeApp();
CefRefPtr<CefClient> makeClient(const std::string& trustedUrl, void* nativeWindow, bool smokeTest, void* testWindow=nullptr);
CefRefPtr<CefClient> makeTrayClient(const std::string& trustedUrl, void* nativeWindow);
void closeTrayBrowser();
bool trayBrowserReadyToClose();
void trayMenuClosed();
bool minimizeToTray();
void toggleTrayWindow();
void dismissTrayMenu();
bool closeBrowsers();
bool smokePassed();
bool browserReadyToClose();
void retryBrowser();
CefRefPtr<CefBrowserHost> browserHost();
// Windowless paint/input helpers stay in the CEF host, outside Application/Core.
void presentBrowser(bool popup, const void* pixels, int width, int height);
void browserPopup(bool visible, const CefRect& bounds);
void browserCursor(CefCursorHandle cursor);
void browserImeBounds(const std::vector<CefRect>& bounds);
int browserPixelAlpha(int x,int y);
inline constexpr unsigned RecoveryFailed=0x8000+42;
inline constexpr wchar_t DragRegionProperty[]=L"MusxiDragRegion";
}
