#include "bridge.hpp"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_message_router.h"
#include "../application/application.hpp"
#include "../../third_party/json.hpp"
#include <map>
#include <iostream>
#include <commctrl.h>
#include <windowsx.h>
#include "include/cef_render_handler.h"
#include "include/cef_display_handler.h"

namespace musxi::cef_adapter {
namespace {
using Json=nlohmann::json;
CefRefPtr<CefBrowser> activeBrowser;
CefRefPtr<CefBrowser> trayBrowser;
bool shuttingDown=false;
bool passed=false;
LRESULT CALLBACK dragHitTest(HWND hwnd,UINT message,WPARAM wp,LPARAM lp,UINT_PTR id,DWORD_PTR parent) {
    if(message==WM_NCHITTEST) {
        auto host=reinterpret_cast<HWND>(parent);
        POINT p{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};ScreenToClient(host,&p);
        auto region=static_cast<HRGN>(GetPropW(host,DragRegionProperty));
        if(region && PtInRegion(region,p.x,p.y))return HTTRANSPARENT;
    }
    if(message==WM_NCDESTROY)RemoveWindowSubclass(hwnd,dragHitTest,id);
    return DefSubclassProc(hwnd,message,wp,lp);
}
Json stateJson(const PlayerState& s) {
    return {{"opened",s.opened},{"playing",s.playing},{"positionMs",s.positionMs},
            {"durationMs",s.durationMs},{"volumePercent",s.volumePercent},{"trackId",s.trackId},
            {"phase",s.phase},{"error",s.error},{"pending",s.pending},{"requestedPlaying",s.requestedPlaying}};
}
class App final : public CefApp, public CefRenderProcessHandler {
public:
    CefRefPtr<CefRenderProcessHandler> GetRenderProcessHandler() override {return this;}
    void OnWebKitInitialized() override {
        router_=CefMessageRouterRendererSide::Create(CefMessageRouterConfig());
    }
    void OnContextCreated(CefRefPtr<CefBrowser> b,CefRefPtr<CefFrame> f,
                          CefRefPtr<CefV8Context> c) override {router_->OnContextCreated(b,f,c);}
    void OnContextReleased(CefRefPtr<CefBrowser> b,CefRefPtr<CefFrame> f,
                           CefRefPtr<CefV8Context> c) override {router_->OnContextReleased(b,f,c);}
    bool OnProcessMessageReceived(CefRefPtr<CefBrowser> b,CefRefPtr<CefFrame> f,
                                  CefProcessId source,CefRefPtr<CefProcessMessage> m) override {
        return router_ && router_->OnProcessMessageReceived(b,f,source,m);
    }
private:
    CefRefPtr<CefMessageRouterRendererSide> router_;
    IMPLEMENT_REFCOUNTING(App);
};
class Client final : public CefClient, public CefLifeSpanHandler,
                     public CefRequestHandler, public CefDragHandler, public CefRenderHandler,
                     public CefDisplayHandler, public CefMessageRouterBrowserSide::Handler {
public:
    Client(std::string url,void* window,bool smoke,void* testWindow,bool tray=false)
        :url_(std::move(url)),window_(static_cast<HWND>(window)),testWindow_(static_cast<HWND>(testWindow)),smoke_(smoke),tray_(tray) {
        router_=CefMessageRouterBrowserSide::Create(CefMessageRouterConfig());
        router_->AddHandler(this,false);
    }
    ~Client() override {router_->RemoveHandler(this);if(dragRegion_)DeleteObject(dragRegion_);}
    CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override {return this;}
    CefRefPtr<CefRequestHandler> GetRequestHandler() override {return this;}
    CefRefPtr<CefDragHandler> GetDragHandler() override {return this;}
    CefRefPtr<CefRenderHandler> GetRenderHandler() override {return testWindow_?this:nullptr;}
    CefRefPtr<CefDisplayHandler> GetDisplayHandler() override {return this;}
    bool OnConsoleMessage(CefRefPtr<CefBrowser>,cef_log_severity_t,const CefString& message,const CefString&,int) override {
        if(smoke_ && message.ToString().rfind("CEF smoke failed:",0)==0)std::cerr<<message.ToString()<<"\n";
        return false;
    }
    void GetViewRect(CefRefPtr<CefBrowser>,CefRect& rect) override {
        RECT r{};GetClientRect(testWindow_,&r);const int dpi=GetDpiForWindow(testWindow_);
        rect=CefRect(0,0,std::max(1,MulDiv(r.right,96,dpi)),std::max(1,MulDiv(r.bottom,96,dpi)));
    }
    bool GetScreenPoint(CefRefPtr<CefBrowser>,int x,int y,int& screenX,int& screenY) override {
        const int dpi=GetDpiForWindow(testWindow_);POINT p{MulDiv(x,dpi,96),MulDiv(y,dpi,96)};
        ClientToScreen(testWindow_,&p);screenX=p.x;screenY=p.y;return true;
    }
    bool GetScreenInfo(CefRefPtr<CefBrowser>,CefScreenInfo& info) override {
        const int dpi=GetDpiForWindow(testWindow_);info.device_scale_factor=static_cast<float>(dpi)/96;
        MONITORINFO monitor{sizeof(monitor)};
        if(!GetMonitorInfoW(MonitorFromWindow(testWindow_,MONITOR_DEFAULTTONEAREST),&monitor))return false;
        const auto convert=[dpi](RECT r){return CefRect(MulDiv(r.left,96,dpi),MulDiv(r.top,96,dpi),MulDiv(r.right-r.left,96,dpi),MulDiv(r.bottom-r.top,96,dpi));};
        info.rect=convert(monitor.rcMonitor);info.available_rect=convert(monitor.rcWork);info.depth=32;info.depth_per_component=8;return true;
    }
    void OnPaint(CefRefPtr<CefBrowser>,PaintElementType type,const RectList&,const void* pixels,int width,int height) override {
        presentBrowser(type==PET_POPUP,pixels,width,height);
    }
    void OnPopupShow(CefRefPtr<CefBrowser>,bool show) override {browserPopup(show,popupBounds_);}
    void OnPopupSize(CefRefPtr<CefBrowser>,const CefRect& bounds) override {popupBounds_=bounds;browserPopup(true,bounds);}
    bool OnCursorChange(CefRefPtr<CefBrowser>,CefCursorHandle cursor,cef_cursor_type_t,const CefCursorInfo&) override {
        if(!testWindow_)return false;browserCursor(cursor);return true;
    }
    void OnImeCompositionRangeChanged(CefRefPtr<CefBrowser>,const CefRange&,const RectList& bounds) override {browserImeBounds(bounds);}
    void OnDraggableRegionsChanged(CefRefPtr<CefBrowser> browser,CefRefPtr<CefFrame> frame,
                                  const std::vector<CefDraggableRegion>& regions) override {
        if(!testWindow_ || !frame->IsMain() || frame->GetURL().ToString()!=url_)return;
        if(!dragRegion_)dragRegion_=CreateRectRgn(0,0,0,0);
        SetRectRgn(dragRegion_,0,0,0,0);
        POINT origin{};
        if(!browser->GetHost()->IsWindowRenderingDisabled())MapWindowPoints(browser->GetHost()->GetWindowHandle(),testWindow_,&origin,1);
        const int dpi=GetDpiForWindow(testWindow_);
        for(const auto& item:regions) {
            const auto& r=item.bounds;
            auto part=CreateRectRgn(origin.x+MulDiv(r.x,dpi,96),origin.y+MulDiv(r.y,dpi,96),
                origin.x+MulDiv(r.x+r.width,dpi,96),origin.y+MulDiv(r.y+r.height,dpi,96));
            CombineRgn(dragRegion_,dragRegion_,part,item.draggable?RGN_OR:RGN_DIFF);DeleteObject(part);
        }
        SetPropW(testWindow_,DragRegionProperty,dragRegion_);
        EnumChildWindows(testWindow_,[](HWND child,LPARAM host)->BOOL {
            SetWindowSubclass(child,dragHitTest,1,static_cast<DWORD_PTR>(host));return TRUE;
        },reinterpret_cast<LPARAM>(testWindow_));
    }
    void OnAfterCreated(CefRefPtr<CefBrowser> browser) override {
        if(tray_){trayBrowser=browser;return;}
        CEF_REQUIRE_UI_THREAD();activeBrowser=browser;
        setApplicationPlayerEvents([this](const char* name,const PlayerState& state) {
            const auto message=Json{{"version",1},{"event",name},{"state",stateJson(state)}}.dump();
            for(const auto& entry:subscriptions_) entry.second->Success(message);
        });
    }
    void OnBeforeClose(CefRefPtr<CefBrowser> browser) override {
        if(tray_) {
            router_->OnBeforeClose(browser);trayBrowser=nullptr;trayMenuClosed();
            if(shuttingDown)PostMessageW(window_,WM_CLOSE,0,0);
            return;
        }
        CEF_REQUIRE_UI_THREAD();setApplicationPlayerEvents({});
        router_->OnBeforeClose(browser);subscriptions_.clear();activeBrowser=nullptr;
        if(testWindow_)RemovePropW(testWindow_,DragRegionProperty);
        if(closing_ || smoke_ || testWindow_) PostMessageW(window_,WM_CLOSE,0,0);
    }
    bool OnBeforePopup(CefRefPtr<CefBrowser>,CefRefPtr<CefFrame>,int,
        const CefString&,const CefString&,CefLifeSpanHandler::WindowOpenDisposition,bool,
        const CefPopupFeatures&,CefWindowInfo&,CefRefPtr<CefClient>&,
        CefBrowserSettings&,CefRefPtr<CefDictionaryValue>&,bool*) override {return true;}
    bool OnBeforeBrowse(CefRefPtr<CefBrowser> b,CefRefPtr<CefFrame> f,
                        CefRefPtr<CefRequest> request,bool,bool) override {
        CEF_REQUIRE_UI_THREAD();router_->OnBeforeBrowse(b,f);
        return !f->IsMain() || request->GetURL().ToString()!=url_;
    }
    bool OnOpenURLFromTab(CefRefPtr<CefBrowser>,CefRefPtr<CefFrame>,
                         const CefString&,CefRequestHandler::WindowOpenDisposition,bool) override {return true;}
    bool OnProcessMessageReceived(CefRefPtr<CefBrowser> b,CefRefPtr<CefFrame> f,
                                  CefProcessId source,CefRefPtr<CefProcessMessage> m) override {
        return router_->OnProcessMessageReceived(b,f,source,m);
    }
    void OnRenderProcessTerminated(CefRefPtr<CefBrowser> b,TerminationStatus,
                                   int,const CefString&) override {
        if(tray_){b->GetHost()->CloseBrowser(true);return;}
        router_->OnRenderProcessTerminated(b);subscriptions_.clear();
        if(dragRegion_)SetRectRgn(dragRegion_,0,0,0,0);
        if(closing_)return;
        if(!recovered_) {recovered_=true;b->Reload();}
        else if(testWindow_)PostMessageW(testWindow_,RecoveryFailed,0,0);
        else if(MessageBoxW(window_,L"界面恢复失败，音乐仍由后台播放。是否重试？",L"Musxi Player",MB_RETRYCANCEL|MB_ICONERROR)==IDRETRY) {
            b->Reload();
        }
    }
    void OnQueryCanceled(CefRefPtr<CefBrowser>,CefRefPtr<CefFrame>,int64_t id) override {
        CEF_REQUIRE_UI_THREAD();subscriptions_.erase(id);
    }
    bool OnQuery(CefRefPtr<CefBrowser>,CefRefPtr<CefFrame> frame,int64_t id,
                 const CefString& request,bool persistent,CefRefPtr<Callback> callback) override {
        CEF_REQUIRE_UI_THREAD();
        if(!frame->IsMain() || frame->GetURL().ToString()!=url_) {
            callback->Failure(403,"Untrusted frame");return true;
        }
        if(request.length()>4096) {callback->Failure(400,"Invalid request");return true;}
        try {
            const auto q=Json::parse(request.ToString());
            if(!q.is_object() || q.value("version",0)!=1 ||
               !q.contains("command") || !q["command"].is_string() ||
               !q.contains("params") || !q["params"].is_object()) {
                callback->Failure(400,"Invalid protocol envelope");return true;
            }
            const auto command=q["command"].get<std::string>();
            const auto& params=q["params"];
            if(tray_) {
                if(persistent || !params.empty()) {callback->Failure(400,"Invalid tray command");return true;}
                if(command=="window.toggleVisibility")toggleTrayWindow();
                else if(command=="window.dismissTrayMenu")dismissTrayMenu();
                else if(command=="window.close")PostMessageW(window_,WM_CLOSE,0,0);
                else {callback->Failure(403,"Command unavailable in tray menu");return true;}
                callback->Success(Json{{"version",1},{"result",{{"enabled",true},{"maximized",false}}}}.dump());return true;
            }
            if(command=="player.subscribe") {
                if(!persistent || !params.empty() || subscriptions_.size()>=16) {
                    callback->Failure(400,"Invalid subscription");return true;
                }
                subscriptions_[id]=callback;
                callback->Success(Json{{"version",1},{"event","player.stateChanged"},
                    {"state",stateJson(applicationPlayerState())}}.dump());
                return true;
            }
            if(persistent) {callback->Failure(400,"Persistent command not allowed");return true;}
            if(command.rfind("window.",0)==0) {
                const bool theme=command=="window.setTheme";
                if(theme) {
                    if(params.size()!=1 || !params.contains("theme") || !params["theme"].is_string()) {
                        callback->Failure(400,"Invalid theme");return true;
                    }
                    const auto value=params["theme"].get<std::string>();
                    if(value!="light" && value!="dark" && value!="glass" && value!="glass-light") {callback->Failure(400,"Invalid theme");return true;}
                    if(testWindow_) {
                        PostMessageW(testWindow_,WM_APP+43,value=="dark" || value=="glass",0);
                        // Background alpha is supplied by CSS and composited by
                        // the windowless host; text and controls remain opaque.
                        RedrawWindow(testWindow_,nullptr,nullptr,RDW_INVALIDATE|RDW_ALLCHILDREN);
                    }
                } else {
                    if(!params.empty()){callback->Failure(400,"Unexpected window parameters");return true;}
                    if(command!="window.getState" && command!="window.minimize" && command!="window.maximize" && command!="window.close" && command!="window.minimizeToTray") {
                        callback->Failure(404,"Unknown window command");return true;
                    }
                    if(testWindow_) {
                        if(command=="window.minimizeToTray" && !minimizeToTray()) {callback->Failure(500,"无法创建系统托盘图标，请重试。");return true;}
                        if(command=="window.minimize")ShowWindow(testWindow_,SW_MINIMIZE);
                        if(command=="window.maximize")ShowWindow(testWindow_,IsZoomed(testWindow_)?SW_RESTORE:SW_MAXIMIZE);
                        if(command=="window.close")PostMessageW(testWindow_,WM_CLOSE,0,0);
                    }
                }
                callback->Success(Json{{"version",1},{"result",{{"enabled",testWindow_!=nullptr},{"maximized",testWindow_ && IsZoomed(testWindow_)}}}}.dump());return true;
            }
            if(command.rfind("library.",0)==0) {
                const auto reply=applicationLibrary(command,params.dump());
                if(reply.code) callback->Failure(reply.code,reply.message);
                else callback->Success(Json{{"version",1},{"result",Json::parse(reply.json)}}.dump());
                return true;
            }
            if(smoke_ && command=="test.pixelAlpha") {
                if(params.size()!=2 || !params.contains("x") || !params.contains("y") ||
                   !params["x"].is_number_unsigned() || !params["y"].is_number_unsigned() ||
                   params["x"].get<uint64_t>()>16384 || params["y"].get<uint64_t>()>16384) {
                    callback->Failure(400,"Invalid pixel coordinates");return true;
                }
                callback->Success(Json{{"version",1},{"result",browserPixelAlpha(params["x"].get<int>(),params["y"].get<int>())}}.dump());return true;
            }
            const bool volume=command=="player.setVolume",seek=command=="player.seek";
            std::uint32_t value=0;
            if(volume || seek) {
                const char* key=volume?"volumePercent":"positionMs";
                if(params.size()!=1 || !params.contains(key) || !params[key].is_number_unsigned() ||
                   params[key].get<std::uint64_t>()>(volume?100ULL:0xffffffffULL)) {
                    callback->Failure(400,"Invalid command parameters");return true;
                }
                value=params[key].get<std::uint32_t>();
            } else if(!params.empty()) {callback->Failure(400,"Unexpected parameters");return true;}
            if(command=="player.getState") {
                // The CEF Browser UI and Application share the native main thread.
                callback->Success(Json{{"version",1},{"result",stateJson(applicationPlayerState())}}.dump());
            } else if(command=="player.pause" || command=="player.resume" || volume || seek) {
                const auto operation=volume?PlayerCommand::SetVolume:seek?PlayerCommand::Seek:
                    command=="player.pause"?PlayerCommand::Pause:PlayerCommand::Resume;
                const auto result=applicationPlayerCommand(operation,value);
                if(!result) {
                    const auto code=result.error==PlayerError::NotReady?409:
                        result.error==PlayerError::InvalidArgument?400:500;
                    callback->Failure(code,code==409?"No playable track is open":"Player command failed");
                } else callback->Success(Json{{"version",1},{"result",stateJson(applicationPlayerState())}}.dump());
            } else if(smoke_ && command=="test.complete") {
                passed=true;callback->Success("{}");PostMessageW(window_,WM_CLOSE,0,0);
            } else callback->Failure(404,"Unknown command");
        } catch(const Json::exception&) {callback->Failure(400,"Invalid JSON or types");}
        catch(const std::exception&) {callback->Failure(500,"Application state unavailable");}
        return true;
    }
    void requestClose() {closing_=true;if(activeBrowser)activeBrowser->GetHost()->CloseBrowser(false);}
private:
    std::string url_;HWND window_,testWindow_;bool smoke_,tray_,closing_=false,recovered_=false;
    HRGN dragRegion_=nullptr;
    CefRect popupBounds_;
    std::map<int64_t,CefRefPtr<Callback>> subscriptions_;
    CefRefPtr<CefMessageRouterBrowserSide> router_;
    IMPLEMENT_REFCOUNTING(Client);
};
}
CefRefPtr<CefApp> makeApp() {return new App;}
CefRefPtr<CefClient> makeClient(const std::string& url,void* window,bool smoke,void* testWindow) {
    passed=false;return new Client(url,window,smoke,testWindow);
}
bool closeBrowsers() {
    CEF_REQUIRE_UI_THREAD();
    shuttingDown=true;closeTrayBrowser();
    if(activeBrowser)static_cast<Client*>(activeBrowser->GetHost()->GetClient().get())->requestClose();
    return !activeBrowser && !trayBrowser;
}
CefRefPtr<CefClient> makeTrayClient(const std::string& url,void* window) {return new Client(url,window,false,nullptr,true);}
void closeTrayBrowser() {if(trayBrowser)trayBrowser->GetHost()->CloseBrowser(false);}
bool trayBrowserReadyToClose() {return trayBrowser && trayBrowser->GetHost()->IsReadyToBeClosed();}
bool smokePassed() {return passed;}
bool browserReadyToClose() {return activeBrowser && activeBrowser->GetHost()->IsReadyToBeClosed();}
void retryBrowser() {if(activeBrowser)activeBrowser->Reload();}
CefRefPtr<CefBrowserHost> browserHost() {return activeBrowser?activeBrowser->GetHost():nullptr;}
}
