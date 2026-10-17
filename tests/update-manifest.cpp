#include "../src/updates/updates.hpp"
#include <iostream>
#include <stdexcept>
using namespace musxi::updates;
void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
int main() {
    try {
        Release r;r.id="20";r.assetId="120";r.version="0.5.0";r.tag="v0.5.0";
        r.assetName="MusxiPlayer-Setup-0.5.0.exe";r.bytes=123;
        const std::string hash(64,'a');
        Json m={{"schemaVersion",1},{"version",r.version},{"architecture","x64"},{"assetName",r.assetName},{"bytes",r.bytes},{"sha256",hash}};
        require(parseInstallerManifest(m.dump(),r).sha256==hash,"valid manifest rejected");
        for(const auto* key:{"schemaVersion","version","architecture","assetName","bytes","sha256"}) {
            auto bad=m;bad.erase(key);bool rejected=false;
            try{parseInstallerManifest(bad.dump(),r);}catch(...){rejected=true;}
            require(rejected,"missing manifest field accepted");
        }
        for(const auto& replacement:Json::array({Json{{"version","0.6.0"}},Json{{"architecture","arm64"}},
            Json{{"assetName","../bad.exe"}},Json{{"bytes",124}},Json{{"bytes",-1}},Json{{"bytes","123"}},
            Json{{"sha256",std::string(64,'A')}},Json{{"sha256",std::string(63,'a')}},Json{{"schemaVersion",1.0}},Json{{"extra",true}}})) {
            auto bad=m;bad.update(replacement);bool rejected=false;
            try{parseInstallerManifest(bad.dump(),r);}catch(...){rejected=true;}
            require(rejected,"unbound or invalid manifest accepted");
        }
        bool rejected=false;try{parseInstallerManifest(std::string(4097,' '),r);}catch(...){rejected=true;}
        require(rejected,"oversized manifest accepted");
        const auto root="https://github.com/Miloscovo/Musxi-Player/releases/";
        Json row={{"id",20},{"tag_name",r.tag},{"draft",false},{"prerelease",false},
            {"published_at","2026-10-17T00:00:00Z"},{"html_url",std::string(root)+"tag/"+r.tag},
            {"assets",Json::array({{{"id",120},{"name",r.assetName},{"size",r.bytes},{"state","uploaded"},
                {"browser_download_url",downloadUrl(r)}}})}};
        const BuildInfo current{"0.4.0","test",true};
        require(selectRelease(Json::array({row}),current).latest->manifestId.empty(),"legacy release guessed manifest");
        r.manifestName="MusxiPlayer-Update-0.5.0.json";
        Json asset={{"id",121},{"name",r.manifestName},{"size",256},{"state","uploaded"},{"browser_download_url",manifestUrl(r)}};
        row["assets"].push_back(asset);
        require(selectRelease(Json::array({row}),current).latest->manifestId=="121","manifest selection failed");
        for(const auto& change:Json::array({Json{{"size",4097}},Json{{"state","new"}},
                Json{{"browser_download_url","https://evil.example/file.json"}},Json{{"id",0}}})) {
            auto bad=row;bad["assets"][1].update(change);
            auto s=selectRelease(Json::array({bad}),current);
            require(s.result=="update_available" && s.latest->manifestId.empty(),"invalid manifest broke fallback");
        }
        row["assets"].push_back(asset);
        require(selectRelease(Json::array({row}),current).latest->manifestId.empty(),"ambiguous manifest accepted");
        std::cout<<"Manifest binding, malformed input and legacy release fallback passed\n";
        return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
