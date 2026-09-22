#include "bridge.hpp"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_message_router.h"
#include "../application/application.hpp"
#include "../../third_party/json.hpp"
#include <map>
#include <commctrl.h>
#include <windowsx.h>

namespace musxi::cef_adapter {
namespace {
using Json=nlohmann::json;
CefRefPtr<CefBrowser> activeBrowser;
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
            {"durationMs",s.durationMs},{"volumePercent",s.volumePercent},{"trackId",s.trackId}};
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
                     public CefRequestHandler, public CefDragHandler, public CefMessageRouterBrowserSide::Handler {
public:
    Client(std::string url,void* window,bool smoke,void* testWindow)
        :url_(std::move(url)),window_(static_cast<HWND>(window)),testWindow_(static_cast<HWND>(testWindow)),smoke_(smoke) {
        router_=CefMessageRouterBrowserSide::Create(CefMessageRouterConfig());
        router_->AddHandler(this,false);
    }
    ~Client() override {router_->RemoveHandler(this);if(dragRegion_)DeleteObject(dragRegion_);}
    CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override {return this;}
    CefRefPtr<CefRequestHandler> GetRequestHandler() override {return this;}
    CefRefPtr<CefDragHandler> GetDragHandler() override {return this;}
    void OnDraggableRegionsChanged(CefRefPtr<CefBrowser> browser,CefRefPtr<CefFrame> frame,
                                  const std::vector<CefDraggableRegion>& regions) override {
        if(!testWindow_ || !frame->IsMain() || frame->GetURL().ToString()!=url_)return;
        if(!dragRegion_)dragRegion_=CreateRectRgn(0,0,0,0);
        SetRectRgn(dragRegion_,0,0,0,0);
        POINT origin{};MapWindowPoints(browser->GetHost()->GetWindowHandle(),testWindow_,&origin,1);
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
        CEF_REQUIRE_UI_THREAD();activeBrowser=browser;
        setApplicationPlayerEvents([this](const char* name,const PlayerState& state) {
            const auto message=Json{{"version",1},{"event",name},{"state",stateJson(state)}}.dump();
            for(const auto& entry:subscriptions_) entry.second->Success(message);
        });
    }
    void OnBeforeClose(CefRefPtr<CefBrowser> browser) override {
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
                    if(value!="light" && value!="dark" && value!="glass") {callback->Failure(400,"Invalid theme");return true;}
                    if(testWindow_) {
                        PostMessageW(testWindow_,WM_APP+43,value!="light",0);
                        // Whole-window alpha preserves Chromium's accelerated child window.
                        auto style=GetWindowLongPtrW(testWindow_,GWL_EXSTYLE);
                        SetWindowLongPtrW(testWindow_,GWL_EXSTYLE,value=="glass"?style|WS_EX_LAYERED:style&~WS_EX_LAYERED);
                        if(value=="glass")SetLayeredWindowAttributes(testWindow_,0,210,LWA_ALPHA);
                        RedrawWindow(testWindow_,nullptr,nullptr,RDW_INVALIDATE|RDW_ALLCHILDREN);
                    }
                } else {
                    if(!params.empty()){callback->Failure(400,"Unexpected window parameters");return true;}
                    if(command!="window.getState" && command!="window.minimize" && command!="window.maximize" && command!="window.close") {
                        callback->Failure(404,"Unknown window command");return true;
                    }
                    if(testWindow_) {
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
    std::string url_;HWND window_,testWindow_;bool smoke_,closing_=false,recovered_=false;
    HRGN dragRegion_=nullptr;
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
    if(!activeBrowser)return true;
    static_cast<Client*>(activeBrowser->GetHost()->GetClient().get())->requestClose();
    return false;
}
bool smokePassed() {return passed;}
bool browserReadyToClose() {return activeBrowser && activeBrowser->GetHost()->IsReadyToBeClosed();}
void retryBrowser() {if(activeBrowser)activeBrowser->Reload();}
}
