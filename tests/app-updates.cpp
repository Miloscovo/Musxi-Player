#include "../src/updates/updates.hpp"
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>

using namespace musxi::updates;
namespace fs=std::filesystem;
void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
Json release(const std::string& version,int number=10,bool pre=true) {
    const auto tag="v"+version,name="MusxiPlayer-Setup-"+version+".exe";
    const std::string root="https://github.com/Miloscovo/Musxi-Player/releases/";
    return {{"id",number},{"tag_name",tag},{"draft",false},{"prerelease",pre},
        {"published_at","2026-10-16T00:00:00Z"},{"body","更新说明\nRelease notes"},{"html_url",root+"tag/"+tag},
        {"assets",Json::array({{{"id",number+100},{"name",name},{"state","uploaded"},{"size",123456},
            {"browser_download_url",root+"download/"+tag+"/"+name}}})}};
}
Json finished(Manager& m) {
    const auto until=std::chrono::steady_clock::now()+std::chrono::seconds(3);
    for(;;){auto s=m.state();if(s["checkStatus"]!="checking")return s;
        require(std::chrono::steady_clock::now()<until,"worker did not complete");std::this_thread::sleep_for(std::chrono::milliseconds(1));}
}
int main(int argc,char** argv) {
    if(argc==2 && std::string(argv[1])=="--cancel-live") {
        std::atomic_bool cancel{false};const auto start=std::chrono::steady_clock::now();
        auto task=std::async(std::launch::async,[&]{return fetchReleases(cancel);});
        std::this_thread::sleep_for(std::chrono::milliseconds(25));cancel=true;
        require(task.wait_for(std::chrono::seconds(3))==std::future_status::ready,"live cancellation did not finish");
        const auto response=task.get();
        std::cout<<response.reason<<" after "<<std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count()<<" ms\n";
        return response.reason=="cancelled"?0:1;
    }
    // Explicit opt-in read-only live probe; never opens a browser or touches account state.
    if(argc==2 && std::string(argv[1])=="--live") {
        std::atomic_bool cancel{false};const auto reply=fetchReleases(cancel);
        if(!reply.reason.empty()){std::cerr<<reply.reason<<'\n';return 1;}
        try {auto selected=selectRelease(Json::parse(reply.body),{"0.0.0","live-probe",true});
            require(selected.latest.has_value(),"no live release");
            std::cout<<selected.result<<" "<<selected.latest->version<<" "<<selected.latest->assetName<<'\n';return 0;
        }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
    }
    auto dir=fs::temp_directory_path()/("musxi-updates-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        require(compareVersions("v0.10.0","0.9.0")==1,"lexical version comparison");
        require(compareVersions("1.0.0-rc.10","1.0.0-rc.2")==1,"numeric prerelease comparison");
        require(compareVersions("1.0.0","1.0.0-rc.1")==1,"stable precedence");
        require(compareVersions("1.0.0+a","v1.0.0+b")==0,"metadata precedence");
        require(compareVersions("1.0.0-alpha","1.0.0-alpha.1")==-1,"prerelease length");
        require(compareVersions("1.0.0-1","1.0.0-alpha")==-1,"numeric vs string prerelease");
        for(const auto* bad:{"0.4","01.0.0","1.0.0-01","1.0.0/evil","1.0.0\n","","1.0.0-"})
            require(!compareVersions(bad,"0.1.0"),"invalid version accepted");
        const BuildInfo current{"0.4.0","commit",true};
        auto rows=Json::array({release("0.3.0"),release("0.5.0",20),release("0.4.0",30)});
        auto selected=selectRelease(rows,current);
        require(selected.result=="update_available" && selected.latest->id=="20" && selected.latest->prerelease,"release selection/pre policy");
        require(selectRelease(Json::array({release("0.4.0")}),current).result=="no_newer_release","equal update");
        require(selectRelease(Json::array({release("0.3.0")}),current).result=="no_newer_release","downgrade update");
        require(selectRelease(Json::array(),current).result=="no_releases","empty releases");
        require(selectRelease(rows,{"0.4.0","dev",false}).result=="development_build","development upgrade offered");
        auto bad=release("0.6.0");bad["draft"]=true;
        require(selectRelease(Json::array({bad,release("0.5.0")}),current).latest->version=="0.5.0","draft accepted");
        bad=release("0.6.0");bad["assets"]=Json::array();
        require(selectRelease(Json::array({bad,release("0.5.0")}),current).result=="no_compatible_asset","fell back from latest missing installer");
        bad=release("0.5.0");bad["assets"].push_back(bad["assets"][0]);
        require(selectRelease(Json::array({bad}),current).result=="no_compatible_asset","ambiguous installer");
        for(const auto* url:{"https://evil.example/installer.exe","http://github.com/Miloscovo/Musxi-Player/a.exe",
                            "https://github.com/Miloscovo/Musxi-Player.evil/releases/a.exe"}) {
            bad=release("0.5.0");bad["assets"][0]["browser_download_url"]=url;
            require(selectRelease(Json::array({bad}),current).result=="no_compatible_asset","untrusted download accepted");
        }
        bad=release("0.5.0");bad["assets"][0]["name"]="MusxiPlayer-Setup-0.5.0-arm64.exe";
        require(selectRelease(Json::array({bad}),current).result=="no_compatible_asset","wrong architecture");
        bad=release("0.5.0");bad["assets"][0]["state"]="new";
        require(selectRelease(Json::array({bad}),current).result=="no_compatible_asset","incomplete asset");
        bad=release("0.5.0");bad["assets"][0]["size"]=-1;
        require(selectRelease(Json::array({bad}),current).result=="no_compatible_asset","invalid asset size");
        bad=release("0.5.0");bad["html_url"]="https://github.com/other/repo/releases/tag/v0.5.0";
        bool failed=false;try{selectRelease(Json::array({bad}),current);}catch(...){failed=true;}
        require(failed,"untrusted release treated as up to date");
        failed=false;try{selectRelease(Json::object(),current);}catch(...){failed=true;}require(failed,"invalid response accepted");
        std::int64_t now=1800000000;std::atomic_int calls{0};std::string opened;
        FetchResult response{Json::array({release("0.5.0",20)}).dump(),{},0};
        const auto clock=[&]{return now;};
        auto fetch=[&](const std::atomic_bool&){++calls;return response;};
        auto open=[&](const std::string& url){opened=url;return true;};
        {
            Manager m(current,dir,false,fetch,open,clock);
            require(m.state()["result"]=="not_checked","initial state");
            require(m.state()["preferences"]["ignoredReleaseId"]=="","missing default ignored preference");
            require(m.command("updates.ignoreRelease",{{"releaseId","20"}}).code==409,"ignored unknown release");
            require(m.command("updates.ignoreRelease",{{"releaseId",20}}).code==400,"numeric ignore ID accepted");
            require(m.command("updates.ignoreRelease",{{"releaseId",""},{"extra",true}}).code==400,"extra ignore params accepted");
            require(m.command("updates.check",{{"url","https://evil.example"}}).code==400,"arbitrary check params");
            require(m.command("updates.check",Json::object()).code==0,"start check");
            auto state=finished(m);require(state["result"]=="update_available" && calls==1,"manager success");
            const auto ignored=m.command("updates.ignoreRelease",{{"releaseId","20"}});
            require(ignored.code==0 && ignored.state["preferences"]["ignoredReleaseId"]=="20","ignore did not return state");
            require(m.command("updates.ignoreRelease",{{"releaseId","19"}}).code==409 &&
                m.state()["preferences"]["ignoredReleaseId"]=="20","stale ignore changed preference");
            require(m.command("updates.check",Json::object()).code==429,"manual flood");
            require(m.command("updates.openDownload",{{"releaseId","stale"},{"assetId","120"}}).code==409,"stale release ID");
            require(m.command("updates.openDownload",{{"releaseId","20"},{"assetId","120"},{"url","evil"}}).code==400,"URL injection");
            require(m.command("updates.openDownload",{{"releaseId","20"},{"assetId","120"}}).code==0 &&
                opened=="https://github.com/Miloscovo/Musxi-Player/releases/download/v0.5.0/MusxiPlayer-Setup-0.5.0.exe","browser download URL");
            require(m.command("updates.openReleaseNotes",{{"releaseId","20"}}).code==0 &&
                opened=="https://github.com/Miloscovo/Musxi-Player/releases/tag/v0.5.0","notes URL");
            require(m.command("updates.setPreferences",{{"autoCheck",false}}).code==0,"save preferences");
            now+=31;response={{},"timeout",0};m.command("updates.check",Json::object());state=finished(m);
            require(state["checkStatus"]=="failed" && state["reason"]=="timeout" && state["result"]=="update_available" && state["stale"]==true,"failure lost old update");
            now+=31;response={{},"rate_limited",now+3600};m.command("updates.check",Json::object());state=finished(m);
            require(state["retryAt"]==now+3600 && state["canCheck"]==false,"rate backoff");
        }
        {
            Manager m(current,dir,false,fetch,open,clock);const auto s=m.state();
            require(s["preferences"]["autoCheck"]==false && s["latest"]["releaseId"]=="20" && s["stale"]==true,"cache/preferences restart");
            require(s["preferences"]["ignoredReleaseId"]=="20","ignore lost on restart");
            require(m.command("updates.check",Json::object()).code==429,"restart bypassed rate backoff");
            require(m.command("updates.ignoreRelease",{{"releaseId",""}}).state["preferences"]["ignoredReleaseId"]=="","ignore not cleared");
        }
        {
            Manager m(current,dir,false,fetch,open,clock);
            require(m.state()["preferences"]["ignoredReleaseId"]=="","ignore clear lost on restart");
            require(m.command("updates.ignoreRelease",{{"releaseId","20"}}).code==0,"ignore cached release failed");
            now+=3601;response={Json::array({release("0.6.0",30)}).dump(),{},0};
            m.command("updates.check",Json::object());const auto s=finished(m);
            require(s["latest"]["releaseId"]=="30" && s["preferences"]["ignoredReleaseId"]=="20","ignore incorrectly applied to new release");
            require(m.command("updates.ignoreRelease",{{"releaseId","20"}}).code==409,"old ignore accepted after release changed");
            // Restore the prior row for the independent cache-tampering check below.
            now+=31;response={Json::array({release("0.5.0",20)}).dump(),{},0};
            m.command("updates.check",Json::object());finished(m);
        }
        // Tampering with cache cannot make it launch an arbitrary URL.
        {std::ifstream in(dir/"update-settings.json");Json saved;in>>saved;in.close();
         saved["releases"][0]["assets"][0]["browser_download_url"]="https://evil.example";
         std::ofstream out(dir/"update-settings.json");out<<saved.dump();}
        {
            Manager m(current,dir,false,fetch,open,clock);
            require(m.state()["result"]=="no_compatible_asset","cache URL not revalidated");
            require(m.command("updates.openDownload",{{"releaseId","20"},{"assetId","120"}}).code==409,"tampered cache downloaded");
        }
        {
            Manager m({"0.4.0","dev",false},dir/"dev",true,fetch,open,clock);
            response={rows.dump(),{},0};m.command("updates.check",Json::object());auto s=finished(m);
            require(s["automaticCheckEnabled"]==false && s["latest"]["downloadAvailable"]==false,"dev automatic/download enabled");
            require(m.command("updates.openDownload",{{"releaseId","20"},{"assetId","120"}}).code==409,"dev download opened");
        }
        {
            // In-flight duplicate is coalesced, and stop waits for cancellation-aware work.
            std::atomic_bool entered{false};std::atomic_int started{0};
            Manager m(current,dir/"cancel",false,[&](const std::atomic_bool& cancel){
                ++started;entered=true;
                while(!cancel)std::this_thread::sleep_for(std::chrono::milliseconds(1));
                return FetchResult{{},"cancelled",0};
            },open,clock);
            m.command("updates.check",Json::object());m.command("updates.check",Json::object());
            while(!entered)std::this_thread::yield();
            m.stop();require(started==1 && m.command("updates.check",Json::object()).code==409,"shutdown/coalescing");
        }
        {
            Manager m(current,dir/"invalid",false,[](const std::atomic_bool&){return FetchResult{"not json",{},0};},open,clock);
            m.command("updates.check",Json::object());require(finished(m)["reason"]=="invalid_data","bad JSON reason");
        }
        {
            const auto profile=dir/"auto";std::atomic_int count{0};
            auto steady=std::chrono::steady_clock::now();
            auto automaticFetch=[&](const std::atomic_bool&){++count;return FetchResult{rows.dump(),{},0};};
            Manager m(current,profile,true,automaticFetch,open,clock,[&]{return steady;});
            m.tick();require(m.state()["checkStatus"]=="idle","auto check before startup delay");
            steady+=std::chrono::seconds(6);m.tick();require(finished(m)["result"]=="update_available" && count==1,"auto check not started");
            now+=2*86400;steady+=std::chrono::hours(48);m.tick();require(count==1,"auto check repeated this run");
            // Persisted successful checks within 24 hours suppress startup traffic.
            now-=2*86400;
            Manager restarted(current,profile,true,automaticFetch,open,clock,[&]{return steady;});
            steady+=std::chrono::seconds(6);restarted.tick();require(count==1,"fresh cache auto requested again");
        }
        {
            const auto blocked=dir/"blocked";{std::ofstream out(blocked);out<<"file";}
            Manager m(current,blocked,false,fetch,open,clock);
            require(m.command("updates.setPreferences",{{"autoCheck",false}}).reason=="storage_error" &&
                m.state()["preferences"]["autoCheck"]==true,"failed settings save not rolled back");
            m.command("updates.check",Json::object());finished(m);
            require(m.command("updates.ignoreRelease",{{"releaseId","20"}}).reason=="storage_error" &&
                m.state()["preferences"]["ignoredReleaseId"]=="","failed ignore save not rolled back");
        }
        {
            // Existing version-1 files continue to load; malformed ignore data is discarded.
            Json saved;{std::ifstream in(dir/"update-settings.json");in>>saved;}
            for(const Json ignored:{Json(nullptr),Json(20),Json("00"),Json("-1"),Json("https://evil.example")}) {
                saved["ignoredReleaseId"]=ignored;
                {std::ofstream out(dir/"update-settings.json");out<<saved.dump();}
                Manager m(current,dir,false,fetch,open,clock);
                require(m.state()["preferences"]["ignoredReleaseId"]=="","invalid stored ignore ID accepted");
            }
            saved.erase("ignoredReleaseId");
            {std::ofstream out(dir/"update-settings.json");out<<saved.dump();}
            Manager m(current,dir,false,fetch,open,clock);
            require(m.state()["preferences"]["autoCheck"]==false && m.state()["preferences"]["ignoredReleaseId"]=="","legacy preferences not loaded");
        }
        {
            Manager m(current,dir/"browser",false,[&](const std::atomic_bool&){return FetchResult{rows.dump(),{},0};},
                [](const std::string&){return false;},clock);
            m.command("updates.check",Json::object());finished(m);
            require(m.command("updates.openReleaseNotes",{{"releaseId","20"}}).reason=="browser_open_failed","browser failure hidden");
        }
        require(!openBrowser("file:///C:/Windows/System32/cmd.exe") && !openBrowser("https://evil.example"),"opener accepts arbitrary URL");
        fs::remove_all(dir);std::cout<<"Application update contracts passed\n";return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';fs::remove_all(dir);return 1;}
}
