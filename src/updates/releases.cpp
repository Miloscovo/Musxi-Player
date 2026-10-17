#include "updates.hpp"
#include <algorithm>
#include <regex>
#include <stdexcept>
#include <vector>

namespace musxi::updates {
namespace {
struct Version { std::string text; std::vector<std::string> core,pre; };
std::vector<std::string> split(const std::string& s) {
    std::vector<std::string> out;std::size_t start=0;
    for(;;){const auto end=s.find('.',start);out.push_back(s.substr(start,end-start));if(end==s.npos)break;start=end+1;}
    return out;
}
bool numeric(const std::string& s) {return !s.empty() && s.find_first_not_of("0123456789")==s.npos;}
std::optional<Version> parse(const std::string& text) {
    if(text.size()>96)return {};
    static const std::regex pattern(R"(^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$)");
    std::smatch m;if(!std::regex_match(text,m,pattern))return {};
    Version v;v.text=text[0]=='v'?text.substr(1):text;v.core={m[1].str(),m[2].str(),m[3].str()};
    if(m[4].matched)v.pre=split(m[4].str());
    for(const auto& n:v.pre)if(numeric(n) && n.size()>1 && n[0]=='0')return {};
    return v;
}
int numberOrder(const std::string& a,const std::string& b) {
    if(a.size()!=b.size())return a.size()<b.size()?-1:1;
    return a==b?0:a<b?-1:1;
}
std::string id(const Json& row,const char* key) {
    if(!row.contains(key) || !row.at(key).is_number_integer())return {};
    try {auto n=row.at(key).get<std::int64_t>();return n>0?std::to_string(n):std::string{};}catch(...){return {};}
}
std::string text(const Json& row,const char* key,std::size_t max) {
    if(!row.contains(key) || !row.at(key).is_string())return {};
    auto s=row.at(key).get<std::string>();
    if(s.size()>max || std::any_of(s.begin(),s.end(),[](unsigned char c){return c<32 && c!='\n' && c!='\r' && c!='\t';}))return {};
    return s;
}
bool installer(const std::string& name,const std::string& version) {
    return name=="MusxiPlayer-Setup-"+version+".exe" || name=="MusxiPlayer-Setup-"+version+"-x64.exe" ||
        (version=="0.1.0" && name=="MusxiPlayer-0.1-Setup-x64.exe");
}
}
std::optional<int> compareVersions(const std::string& left,const std::string& right) {
    auto a=parse(left),b=parse(right);if(!a || !b)return {};
    for(std::size_t i=0;i<3;++i)if(auto order=numberOrder(a->core[i],b->core[i]))return order;
    if(a->pre.empty() || b->pre.empty())return a->pre.empty()==b->pre.empty()?0:a->pre.empty()?1:-1;
    for(std::size_t i=0;i<std::min(a->pre.size(),b->pre.size());++i) {
        const auto& x=a->pre[i];const auto& y=b->pre[i];if(x==y)continue;
        const bool nx=numeric(x),ny=numeric(y);
        return nx && ny?numberOrder(x,y):nx!=ny?(nx?-1:1):(x<y?-1:1);
    }
    return a->pre.size()==b->pre.size()?0:a->pre.size()<b->pre.size()?-1:1;
}
std::string releaseUrl(const Release& r){return "https://github.com/Miloscovo/Musxi-Player/releases/tag/"+r.tag;}
std::string downloadUrl(const Release& r){return "https://github.com/Miloscovo/Musxi-Player/releases/download/"+r.tag+"/"+r.assetName;}
std::string manifestUrl(const Release& r){return "https://github.com/Miloscovo/Musxi-Player/releases/download/"+r.tag+"/"+r.manifestName;}
InstallerManifest parseInstallerManifest(const std::string& body,const Release& r) {
    if(body.empty() || body.size()>4096 || r.assetId.empty())throw std::runtime_error("invalid_manifest");
    try {
        const auto m=Json::parse(body);
        if(!m.is_object() || m.size()!=6 || m.at("schemaVersion")!=1 || !m.at("schemaVersion").is_number_integer() ||
            m.at("version")!=r.version || m.at("architecture")!="x64" || m.at("assetName")!=r.assetName ||
            !m.at("bytes").is_number_unsigned() || m.at("bytes").get<std::uint64_t>()!=r.bytes ||
            !m.at("sha256").is_string())throw std::runtime_error("invalid_manifest");
        auto hash=m.at("sha256").get<std::string>();
        if(hash.size()!=64 || hash.find_first_not_of("0123456789abcdef")!=hash.npos)throw std::runtime_error("invalid_manifest");
        return {hash};
    }catch(...){throw std::runtime_error("invalid_manifest");}
}
Selection selectRelease(const Json& releases,const BuildInfo& current) {
    if(!releases.is_array() || releases.size()>500)throw std::runtime_error("invalid_data");
    std::optional<Release> latest;
    bool sawPublished=false;
    for(const auto& row:releases) {
        if(!row.is_object() || !row.contains("draft") || !row["draft"].is_boolean())throw std::runtime_error("invalid_data");
        if(row["draft"].get<bool>())continue;
        sawPublished=true;
        auto tag=text(row,"tag_name",96);auto version=parse(tag);
        if(!version || !row.contains("prerelease") || !row["prerelease"].is_boolean())continue;
        Release r;r.id=id(row,"id");r.tag=tag;r.version=version->text;r.prerelease=row["prerelease"].get<bool>();
        r.publishedAt=text(row,"published_at",64);r.notes=text(row,"body",1024*1024);
        if(r.notes.size()>8192) {
            std::size_t end=8192;
            while(end>0 && (static_cast<unsigned char>(r.notes[end])&0xc0)==0x80)--end;
            r.notes.resize(end);
        }
        if(r.id.empty() || r.publishedAt.empty() || text(row,"html_url",256)!=releaseUrl(r))continue;
        if(latest && *compareVersions(r.version,latest->version)<=0)continue;
        if(!row.contains("assets") || !row["assets"].is_array() || row["assets"].size()>1000)throw std::runtime_error("invalid_data");
        unsigned matches=0;
        for(const auto& asset:row["assets"]) {
            if(!asset.is_object())continue;
            const auto name=text(asset,"name",200);if(!installer(name,r.version))continue;
            // Ambiguous installer names are rejected even when one is malformed.
            ++matches;
            const auto assetId=id(asset,"id");
            if(assetId.empty() || text(asset,"state",32)!="uploaded" || !asset.contains("size") || !asset["size"].is_number_integer())continue;
            std::int64_t size=0;try{size=asset["size"].get<std::int64_t>();}catch(...){continue;}
            if(size<=0 || size>=2147483648LL)continue;
            auto candidate=r;candidate.assetName=name;
            if(text(asset,"browser_download_url",512)!=downloadUrl(candidate))continue;
            r.assetId=assetId;r.assetName=name;r.bytes=static_cast<std::uint64_t>(size);
        }
        if(matches!=1){r.assetId.clear();r.assetName.clear();r.bytes=0;}
        // Older releases keep the browser-only path. Never guess a manifest URL.
        unsigned manifests=0;
        for(const auto& asset:row["assets"]) {
            if(!asset.is_object() || text(asset,"name",200)!="MusxiPlayer-Update-"+r.version+".json")continue;
            ++manifests;
            auto candidate=r;candidate.manifestName=text(asset,"name",200);
            const auto aid=id(asset,"id");
            if(aid.empty() || text(asset,"state",32)!="uploaded" || !asset.contains("size") || !asset["size"].is_number_integer())continue;
            const auto size=asset["size"].get<std::int64_t>();
            if(size<=0 || size>4096 || text(asset,"browser_download_url",512)!=manifestUrl(candidate))continue;
            r.manifestId=aid;r.manifestName=candidate.manifestName;r.manifestBytes=static_cast<std::uint64_t>(size);
        }
        if(manifests!=1){r.manifestId.clear();r.manifestName.clear();r.manifestBytes=0;}
        latest=r;
    }
    if(sawPublished && !latest)throw std::runtime_error("invalid_data");
    if(!current.release)return {"development_build",latest};
    if(!parse(current.version))throw std::runtime_error("invalid_current_version");
    if(!latest)return {"no_releases",{}};
    if(*compareVersions(latest->version,current.version)<=0)return {"no_newer_release",latest};
    return {latest->assetId.empty()?"no_compatible_asset":"update_available",latest};
}
}
