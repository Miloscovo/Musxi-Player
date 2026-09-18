#include "bridge.hpp"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_message_router.h"
#include "../application/application.hpp"
#include "../../third_party/json.hpp"

namespace musxi::cef_adapter {
namespace {
using Json=nlohmann::json;
CefRefPtr<CefBrowser> activeBrowser;
bool passed=false;
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
                     public CefRequestHandler, public CefMessageRouterBrowserSide::Handler {
public:
    Client(std::string url,void* window,bool smoke)
        :url_(std::move(url)),window_(static_cast<HWND>(window)),smoke_(smoke) {
        router_=CefMessageRouterBrowserSide::Create(CefMessageRouterConfig());
        router_->AddHandler(this,false);
    }
    ~Client() override {router_->RemoveHandler(this);}
    CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override {return this;}
    CefRefPtr<CefRequestHandler> GetRequestHandler() override {return this;}
    void OnAfterCreated(CefRefPtr<CefBrowser> browser) override {
        CEF_REQUIRE_UI_THREAD();activeBrowser=browser;
    }
    void OnBeforeClose(CefRefPtr<CefBrowser> browser) override {
        CEF_REQUIRE_UI_THREAD();router_->OnBeforeClose(browser);activeBrowser=nullptr;
        if(closing_ || smoke_) PostMessageW(window_,WM_CLOSE,0,0);
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
                                   int,const CefString&) override {router_->OnRenderProcessTerminated(b);}
    bool OnQuery(CefRefPtr<CefBrowser>,CefRefPtr<CefFrame> frame,int64_t,
                 const CefString& request,bool persistent,CefRefPtr<Callback> callback) override {
        CEF_REQUIRE_UI_THREAD();
        if(!frame->IsMain() || frame->GetURL().ToString()!=url_) {
            callback->Failure(403,"Untrusted frame");return true;
        }
        if(persistent || request.length()>4096) {callback->Failure(400,"Invalid request");return true;}
        try {
            const auto q=Json::parse(request.ToString());
            if(!q.is_object() || q.value("version",0)!=1 ||
               !q.contains("command") || !q["command"].is_string() ||
               !q.contains("params") || !q["params"].is_object() || !q["params"].empty()) {
                callback->Failure(400,"Invalid protocol envelope");return true;
            }
            const auto command=q["command"].get<std::string>();
            if(command=="player.getState") {
                // The CEF Browser UI and Application share the native main thread.
                const auto s=applicationPlayerState();
                callback->Success(Json{{"version",1},{"result",{
                    {"opened",s.opened},{"playing",s.playing},{"positionMs",s.positionMs},
                    {"durationMs",s.durationMs},{"volumePercent",s.volumePercent},
                    {"trackId",s.trackId}}}}.dump());
            } else if(smoke_ && command=="test.complete") {
                passed=true;callback->Success("{}");PostMessageW(window_,WM_CLOSE,0,0);
            } else callback->Failure(404,"Unknown command");
        } catch(const Json::exception&) {callback->Failure(400,"Invalid JSON or types");}
        catch(const std::exception&) {callback->Failure(500,"Application state unavailable");}
        return true;
    }
    void requestClose() {closing_=true;if(activeBrowser)activeBrowser->GetHost()->CloseBrowser(false);}
private:
    std::string url_;HWND window_;bool smoke_,closing_=false;
    CefRefPtr<CefMessageRouterBrowserSide> router_;
    IMPLEMENT_REFCOUNTING(Client);
};
}
CefRefPtr<CefApp> makeApp() {return new App;}
CefRefPtr<CefClient> makeClient(const std::string& url,void* window,bool smoke) {
    passed=false;return new Client(url,window,smoke);
}
bool closeBrowsers() {
    CEF_REQUIRE_UI_THREAD();
    if(!activeBrowser)return true;
    static_cast<Client*>(activeBrowser->GetHost()->GetClient().get())->requestClose();
    return false;
}
bool smokePassed() {return passed;}
}
