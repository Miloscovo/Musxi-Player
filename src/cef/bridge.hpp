#pragma once
#include "include/cef_app.h"
#include "include/cef_client.h"
#include "include/cef_browser.h"
#include <functional>
namespace musxi { struct LibraryReply; }
namespace musxi::cef_adapter {
CefRefPtr<CefApp> makeApp();
CefRefPtr<CefClient> makeClient(const std::string& trustedUrl, void* nativeWindow, bool smokeTest, void* testWindow=nullptr);
CefRefPtr<CefClient> makeTrayClient(const std::string& trustedUrl, void* nativeWindow);
CefRefPtr<CefClient> makeDesktopLyricsClient(const std::string& trustedUrl,void* nativeWindow,void* lyricsWindow);
CefRefPtr<CefBrowserHost> desktopLyricsBrowserHost();
void closeDesktopLyricsBrowser();
bool desktopLyricsBrowserReadyToClose();
void desktopLyricsClosed();
void presentDesktopLyrics(const void* pixels,int width,int height);
std::string desktopLyricsDiagnostics();
std::string desktopLyricsState();
void publishDesktopLyrics(const std::string& state);
musxi::LibraryReply desktopLyricsCommand(const std::string& command,const std::string& params);
void closeTrayBrowser();
bool trayBrowserReadyToClose();
void trayMenuClosed();
bool minimizeToTray();
void toggleTrayWindow();
musxi::LibraryReply shortcutsCommand(const std::string& command,const std::string& params);
musxi::LibraryReply wallpaperCommand(const std::string& command,const std::string& params);
void resetShortcutInput();
void rememberShortcutVolume(int volume);
void dismissTrayMenu();
bool mainWindowCompact();
bool setMainWindowCompact(bool compact,int height,std::function<void(bool)> complete={});
void finishMainWindowTransition();
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
inline constexpr wchar_t ResizeViewProperty[]=L"MusxiResizeView";
}
