#include "../src/application/application.hpp"
#include "../src/updates/fixture.hpp"
#include "../third_party/json.hpp"
#include <windows.h>
#include <iostream>

namespace {
using Json=nlohmann::json;
HWND window=nullptr;
bool passed=false;
bool readyPassed=false;
bool fixtureMode=false;
bool downloadMode=false,downloadStarted=false;
void ready(void* handle) {
    window=static_cast<HWND>(handle);
    const auto initial=musxi::applicationUpdates("updates.getState","{}");
    if(fixtureMode) {
        readyPassed=!initial.code && Json::parse(initial.json)["automaticCheckEnabled"]==true &&
            musxi::applicationUpdates("updates.ignoreRelease",R"({"releaseId":""})").code==0 &&
            musxi::applicationUpdates("updates.check","{}").code==0;
        if(!readyPassed)PostMessageW(window,WM_CLOSE,0,0);
        return;
    }
    if(initial.code || Json::parse(initial.json)["automaticCheckEnabled"]!=false ||
        Json::parse(initial.json)["preferences"]["ignoredReleaseId"]!="" ||
        musxi::applicationUpdates("updates.ignoreRelease",R"({"releaseId":""})").message!="storage_error" ||
        musxi::applicationUpdates("updates.ignoreRelease",R"({"releaseId":"1"})").code!=409 ||
        musxi::applicationUpdates("updates.check","{broken").code!=400 ||
        musxi::applicationUpdates("updates.check",R"({"url":"https://evil.example"})").code!=400 ||
        musxi::applicationUpdates("updates.setPreferences",R"({"autoCheck":false})").message!="storage_error" ||
        musxi::applicationUpdates("updates.check","{}").code!=0) {
        PostMessageW(window,WM_CLOSE,0,0);return;
    }
    readyPassed=true;
}
void tick() {
    if(!readyPassed)return;
    const auto reply=musxi::applicationUpdates("updates.getState","{}");
    const auto state=Json::parse(reply.json);
    if(state["checkStatus"]=="checking")return;
    if(fixtureMode) {
        if(downloadMode) {
            if(!downloadStarted) {
                passed=state["capabilities"]["installerDownload"]==true && state["latest"]["inAppDownloadAvailable"]==true &&
                    musxi::applicationUpdates("updates.startDownload",R"({"releaseId":"20","assetId":"120"})").code==0;
                downloadStarted=true;if(!passed)PostMessageW(window,WM_CLOSE,0,0);return;
            }
            if(state["download"]["canInstall"]!=true)return;
            const auto installed=musxi::applicationUpdates("updates.install",R"({"releaseId":"20","assetId":"120"})");
            const auto diagnostics=musxi::applicationUpdates("updates.fixtureDiagnostics","{}");
            passed=!installed.code && !diagnostics.code &&
                Json::parse(installed.json)["download"]["status"]=="installing" &&
                Json::parse(diagnostics.json)["installedReleases"]==Json::array({"20"});
            PostMessageW(window,WM_CLOSE,0,0);return;
        }
        const auto ignored=musxi::applicationUpdates("updates.ignoreRelease",R"({"releaseId":"20"})");
        const auto opened=musxi::applicationUpdates("updates.openDownload",R"({"releaseId":"20","assetId":"120"})");
        const auto diagnostics=musxi::applicationUpdates("updates.fixtureDiagnostics","{}");
        passed=state["result"]=="update_available" && !ignored.code && !opened.code && !diagnostics.code &&
            Json::parse(ignored.json)["preferences"]["ignoredReleaseId"]=="20" &&
            Json::parse(diagnostics.json)["openedUrls"].size()==1;
        PostMessageW(window,WM_CLOSE,0,0);return;
    }
    passed=state["checkStatus"]=="failed" && state["reason"]=="network_disabled";
    PostMessageW(window,WM_CLOSE,0,0);
}
}
int main(int argc,char** argv) {
    if(musxi::applicationUpdates("updates.getState","{}").code!=409)return 1;
    musxi::HostHooks hooks{};hooks.ready=ready;hooks.tick=tick;hooks.connectCloud=false;hooks.testProfile=true;
    fixtureMode=argc>=2;downloadMode=argc==3 && std::string(argv[2])=="--download";
    if(fixtureMode)hooks.updateFixture=musxi::updates::Fixture::load(std::filesystem::u8path(argv[1]));
    musxi::setHostHooks(hooks);
    const auto result=musxi::runApplication(GetModuleHandleW(nullptr));
    musxi::setHostHooks({});
    if(result || !passed || musxi::applicationUpdates("updates.getState","{}").code!=409)return 1;
    std::cout<<"Application update lifecycle and offline host boundary passed\n";return 0;
}
