#pragma once
#include "../../third_party/json.hpp"
#include <filesystem>
#include <fstream>
#include <set>
#include <regex>
#include <algorithm>
#include <cwchar>
#include <vector>
#include <list>
#include <mutex>
#include "wallpaper_store.hpp"

namespace musxi::wallpaper {
namespace fs = std::filesystem;
using Json = nlohmann::json;
inline std::string utf8(const fs::path& path) {
    const auto value=path.u8string();return {value.begin(),value.end()};
}
inline fs::path containedFile(const fs::path& root,const std::string& name) {
    if(name.empty() || name.find('\0')!=std::string::npos || name.find(':')!=std::string::npos)return {};
    const auto relative=fs::u8path(name);
    if(relative.is_absolute() || relative.has_root_path())return {};
    std::error_code ec;const auto base=fs::canonical(root,ec);if(ec)return {};
    const auto file=fs::canonical(base/relative,ec);if(ec || !fs::is_regular_file(file,ec))return {};
    auto a=base.begin(),b=file.begin();
    for(;a!=base.end();++a,++b)if(b==file.end() || _wcsicmp(a->c_str(),b->c_str())!=0)return {};
    return file;
}
inline Json readProject(const fs::path& path) {
    std::error_code ec;if(fs::file_size(path,ec)>1024*1024 || ec)return nullptr;
    std::ifstream stream(path);auto value=Json::parse(stream,nullptr,false);
    return value.is_object()?value:Json(nullptr);
}
struct Project { std::string id,title,type,file,preview;fs::path directory; };
struct ScanDirectory { fs::path directory; size_t count=0,invalid=0,unsupported=0,missing=0;std::string error; };
inline std::vector<Project> scanProjects(const std::vector<fs::path>& roots,std::vector<ScanDirectory>* reports=nullptr) {
    std::vector<Project> result;std::set<fs::path> seen;
    ScanDirectory report;
    auto add=[&](const fs::path& directory) {
        try {
        std::error_code ec;const auto canonical=fs::canonical(directory,ec);if(ec || !seen.insert(canonical).second)return;
        if(!fs::exists(canonical/L"project.json",ec))return;
        const auto project=readProject(canonical/L"project.json");if(project.is_null()){++report.invalid;return;}
        auto type=project.value("type",std::string{});
        std::transform(type.begin(),type.end(),type.begin(),[](unsigned char c){return static_cast<char>(std::tolower(c));});
        if(type!="scene" && type!="video"){++report.unsupported;return;}
        const auto file=project.value("file",std::string{});
        if(type=="video" && containedFile(canonical,file).empty()){++report.missing;return;}
        if(type=="scene" && containedFile(canonical,file).empty() && containedFile(canonical,"scene.pkg").empty()
            && containedFile(canonical,"scenes/scene.pkg").empty() && containedFile(canonical,"gifscene.pkg").empty()){++report.missing;return;}
        auto preview=project.value("preview",std::string{});auto ext=fs::u8path(preview).extension().wstring();
        std::transform(ext.begin(),ext.end(),ext.begin(),towlower);
        if((ext!=L".png" && ext!=L".apng" && ext!=L".jpg" && ext!=L".jpeg" && ext!=L".webp" && ext!=L".gif")
            || containedFile(canonical,preview).empty())preview.clear();
        result.push_back({{},project.value("title",utf8(canonical.filename())),type,file,preview,canonical});
        ++report.count;
        } catch(const Json::exception&) { ++report.invalid; }
    };
    for(const auto& root:roots) {
        report={root};
        try {
            add(root);
            std::error_code ec;
            for(fs::directory_iterator it(root,ec),end;it!=end && !ec;it.increment(ec)) {
                if(it->is_directory(ec))add(it->path());
            }
            if(ec)report.error=ec==std::errc::no_such_file_or_directory?"目录不存在":"无法读取目录，请检查访问权限";
        } catch(const Json::exception&) { /* A malformed third-party project must not hide the rest of the library. */ }
        if(reports)reports->push_back(report);
    }
    std::sort(result.begin(),result.end(),[](const Project& a,const Project& b){return a.title<b.title;});return result;
}
inline std::vector<fs::path> steamLibraries(const fs::path& steam) {
    std::vector<fs::path> result{steam};std::ifstream input(steam/L"steamapps/libraryfolders.vdf");
    const std::string text((std::istreambuf_iterator<char>(input)),{});
    const std::regex path(R"vdf("path"\s*"((?:\\.|[^"\\])*)")vdf");
    for(std::sregex_iterator it(text.begin(),text.end(),path),end;it!=end;++it) {
        const auto escaped=(*it)[1].str();std::string value;
        for(size_t i=0;i<escaped.size();++i){if(escaped[i]=='\\' && i+1<escaped.size())++i;value+=escaped[i];}
        result.push_back(fs::u8path(value));
    }
    return result;
}
inline std::vector<fs::path> wallpaperRoots(const fs::path& directory) {
    std::vector<fs::path> result{directory};
    auto add=[&](const fs::path& path){std::error_code ec;if(fs::is_directory(path,ec))result.push_back(path);};
    for(const auto& library:steamLibraries(directory)) {
        add(library/L"steamapps/workshop/content/431960");
        add(library/L"workshop/content/431960");
        add(library/L"content/431960");
        for(const auto* folder:{L"defaultprojects",L"myprojects"}) {
            add(library/folder);add(library/L"projects"/folder);
            add(library/L"common/wallpaper_engine/projects"/folder);
            add(library/L"steamapps/common/wallpaper_engine/projects"/folder);
        }
    }
    return result;
}
class ThumbnailCache {
    struct Entry {fs::path path;fs::file_time_type modified;uintmax_t size;Image image;};
    std::mutex mutex_;std::list<Entry> entries_;size_t bytes_=0;
public:
    void clear(){std::lock_guard lock(mutex_);entries_.clear();bytes_=0;}
    template<class Loader> Image get(const fs::path& path,Loader load) {
        const auto modified=fs::last_write_time(path);const auto size=fs::file_size(path);
        {
        std::lock_guard lock(mutex_);
        for(auto it=entries_.begin();it!=entries_.end();++it)if(it->path==path) {
            if(it->modified==modified && it->size==size){entries_.splice(entries_.begin(),entries_,it);return entries_.front().image;}
            bytes_-=it->image.bytes.size();entries_.erase(it);break;
        }
        }
        auto image=load(path);
        if(fs::last_write_time(path)!=modified || fs::file_size(path)!=size)return image;
        std::lock_guard lock(mutex_);
        if(image.bytes.size()<=32*1024*1024) {
            while(!entries_.empty() && (entries_.size()>=128 || bytes_+image.bytes.size()>32*1024*1024)) {bytes_-=entries_.back().image.bytes.size();entries_.pop_back();}
            bytes_+=image.bytes.size();entries_.push_front({path,modified,size,image});
        }
        return image;
    }
};
}
