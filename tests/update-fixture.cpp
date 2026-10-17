#include "../src/updates/fixture.hpp"
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>

using namespace musxi::updates;
namespace fs=std::filesystem;
namespace {
void require(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
Json finished(Manager& manager) {
    const auto until=std::chrono::steady_clock::now()+std::chrono::seconds(3);
    for(;;) {
        auto state=manager.state();if(state["checkStatus"]!="checking")return state;
        require(std::chrono::steady_clock::now()<until,"fixture worker timeout");
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}
void write(const fs::path& path,const Json& value){std::ofstream out(path,std::ios::binary);out<<value.dump();}
}
int main(int argc,char** argv) {
    if(argc!=2)return 1;
    const auto previous=fs::current_path();
    const auto dir=fs::temp_directory_path()/("musxi-update-fixture-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        Json sample;{std::ifstream in(fs::u8path(argv[1]));in>>sample;}
        sample["fetch"]={{"delaySeconds",0}};
        fs::create_directories(dir);fs::current_path(dir);
        const auto path=dir/fs::u8path(u8"更新 fixture.json");
        auto load=[&](const Json& data){write(path,data);return Fixture::load(path,{"0.4.0","compiled-dev",false});};
        auto invalid=[&](const Json& data){bool rejected=false;try{(void)load(data);}catch(...){rejected=true;}require(rejected,"invalid fixture accepted");};
        // Memory mode must neither read nor overwrite a settings file in the cwd.
        const Json sentinel={{"version",1},{"autoCheck",false},{"ignoredReleaseId","999"},
            {"lastAttemptAt",0},{"lastSuccessAt",0},{"retryAt",0},{"releases",Json::array()}};
        write(dir/"update-settings.json",sentinel);
        std::int64_t now=1800000000;auto clock=[&]{return now;};
        auto steady=std::chrono::steady_clock::now();
        {
            auto data=sample;
            data["fetch"]=Json::array({Json::object(),{{"reason","timeout"}},{{"reason","rate_limited"},{"retryAfter",3600}},
                {{"releases",Json::array()}}});
            auto fixture=load(data);auto manager=fixture->createManager(clock,[&]{return steady;});
            auto state=manager->state();
            require(state["current"]["releaseBuild"]==true && state["current"]["commit"]=="update-fixture","current override missing");
            require(state["preferences"]["autoCheck"]==true && state["preferences"]["ignoredReleaseId"]=="","fixture read profile");
            manager->tick();require(fixture->diagnostics()["fetchCount"]==0,"early automatic fixture fetch");
            steady+=std::chrono::seconds(6);manager->tick();state=finished(*manager);
            require(state["result"]=="update_available" && state["latest"]["releaseId"]=="20","real selection not used");
            require(manager->command("updates.ignoreRelease",{{"releaseId","20"}}).code==0,"memory ignore failed");
            require(manager->command("updates.setPreferences",{{"autoCheck",false}}).code==0,"memory preferences failed");
            require(manager->command("updates.openDownload",{{"releaseId","20"},{"assetId","120"}}).code==0,"fixture download failed");
            require(manager->command("updates.openReleaseNotes",{{"releaseId","20"}}).code==0,"fixture notes failed");
            const auto urls=fixture->diagnostics()["openedUrls"];
            require(urls.size()==2 && urls[0]=="https://github.com/Miloscovo/Musxi-Player/releases/download/v0.5.0/MusxiPlayer-Setup-0.5.0.exe" &&
                urls[1]=="https://github.com/Miloscovo/Musxi-Player/releases/tag/v0.5.0","fixture URL not recorded");
            require(manager->command("updates.openDownload",{{"releaseId","19"},{"assetId","120"}}).code==409 &&
                fixture->diagnostics()["openedUrls"].size()==2,"stale URL recorded");
            require(manager->command("updates.check",Json::object()).code==429,"fixture bypassed throttling");
            now+=31;manager->command("updates.check",Json::object());state=finished(*manager);
            require(state["reason"]=="timeout" && state["stale"]==true && state["result"]=="update_available","fixture lost last success on failure");
            now+=31;manager->command("updates.check",Json::object());state=finished(*manager);
            require(state["retryAt"]==now+3600 && state["reason"]=="rate_limited","fixture retryAfter is not relative seconds");
            now+=3601;manager->command("updates.check",Json::object());state=finished(*manager);
            require(state["result"]=="no_releases" && state["preferences"]["ignoredReleaseId"]=="20","fixture sequence ignored");
            now+=31;manager->command("updates.check",Json::object());finished(*manager);
            require(fixture->diagnostics()["fetchCount"]==5,"last fixture response not repeated");
            require(manager->command("updates.ignoreRelease",{{"releaseId",""}}).code==0,"cannot clear without latest");
        }
        {
            auto data=sample;auto newer=data["releases"][0];
            newer["id"]=30;newer["tag_name"]="v0.6.0";newer["html_url"]="https://github.com/Miloscovo/Musxi-Player/releases/tag/v0.6.0";
            newer["assets"]=Json::array();data["fetch"]=Json::array({Json::object(),{{"releases",Json::array({newer})}}});
            auto fixture=load(data);auto manager=fixture->createManager(clock);
            manager->command("updates.check",Json::object());finished(*manager);
            manager->command("updates.ignoreRelease",{{"releaseId","20"}});
            now+=31;manager->command("updates.check",Json::object());const auto state=finished(*manager);
            require(state["result"]=="no_compatible_asset" && state["latest"]["releaseId"]=="30","fixture asset validation bypassed");
            require(manager->command("updates.ignoreRelease",{{"releaseId","20"}}).code==409,"fixture stale ignore accepted");
        }
        {
            auto data=sample;data["current"]["releaseBuild"]=false;
            auto fixture=load(data);auto manager=fixture->createManager(clock);
            manager->command("updates.check",Json::object());const auto state=finished(*manager);
            require(state["result"]=="development_build" && state["automaticCheckEnabled"]==false,"fixture dev identity lost");
            require(manager->command("updates.openDownload",{{"releaseId","20"},{"assetId","120"}}).code==409,"fixture dev download allowed");
        }
        {
            auto data=sample;data["fetch"]["delaySeconds"]=60;
            auto fixture=load(data);auto manager=fixture->createManager(clock);
            manager->command("updates.check",Json::object());
            require(manager->state()["checkStatus"]=="checking","fixture delay ignored");
            const auto start=std::chrono::steady_clock::now();manager->stop();
            require(std::chrono::steady_clock::now()-start<std::chrono::milliseconds(500),"fixture shutdown blocked");
            require(manager->state()["reason"]=="cancelled","fixture cancellation lost");
        }
        // A formal binary rejects the override before opening even a nonexistent file.
        bool rejected=false;try{Fixture::load(dir/"missing.json",{"0.4.0","release",true});}
        catch(const std::exception& error){rejected=std::string(error.what())=="update_fixture_forbidden_in_release_build";}
        require(rejected,"release build accepted fixture");
        for(const auto* field:{"delaySeconds","retryAfter"})for(const Json value:{Json(-1),Json(1.5),Json("1"),Json(86401)}) {
            auto data=sample;data["fetch"][field]=value;invalid(data);
        }
        auto bad=sample;bad["fetch"]["reason"]="typo";invalid(bad);
        bad=sample;bad["current"]["releaseBuild"]="true";invalid(bad);
        bad=sample;bad["current"]["version"]="invalid";invalid(bad);
        bad=sample;bad["schemaVersion"]=2;invalid(bad);
        bad=sample;bad["unknown"]=true;invalid(bad);
        bad=sample;bad["releases"][0]["html_url"]="https://evil.example";invalid(bad);
        bad=sample;bad["releases"]=Json::object();invalid(bad);
        bad=sample;bad["fetch"]=Json::array();invalid(bad);
        for(const std::string body:{std::string("{broken"),std::string(1024*1024+1,' ')}) {
            {std::ofstream out(path,std::ios::binary);out<<body;}
            rejected=false;try{Fixture::load(path);}catch(...){rejected=true;}require(rejected,"unbounded or malformed input accepted");
        }
        Json saved;{std::ifstream in(dir/"update-settings.json");in>>saved;}
        require(saved==sentinel && !fs::exists(dir/"update-settings.json.tmp"),"fixture wrote profile");
        fs::current_path(previous);fs::remove_all(dir);
        std::cout<<"Development update fixture contracts passed\n";return 0;
    }catch(const std::exception& error) {
        fs::current_path(previous);fs::remove_all(dir);std::cerr<<error.what()<<'\n';return 1;
    }
}
