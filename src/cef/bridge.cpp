#include "bridge.hpp"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_message_router.h"
#include "../application/application.hpp"
#include "../../third_party/json.hpp"
#include <map>

namespace musxi::cef_adapter {
namespace {
using Json=nlohmann::json;
CefRefPtr<CefBrowser> activeBrowser;
bool passed=false;
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
        setApplicationPlayerEvents([this](const char* name,const PlayerState& state) {
            const auto message=Json{{"version",1},{"event",name},{"state",stateJson(state)}}.dump();
            for(const auto& entry:subscriptions_) entry.second->Success(message);
        });
    }
    void OnBeforeClose(CefRefPtr<CefBrowser> browser) override {
        CEF_REQUIRE_UI_THREAD();setApplicationPlayerEvents({});
        router_->OnBeforeClose(browser);subscriptions_.clear();activeBrowser=nullptr;
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
    std::string url_;HWND window_;bool smoke_,closing_=false;
    std::map<int64_t,CefRefPtr<Callback>> subscriptions_;
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
