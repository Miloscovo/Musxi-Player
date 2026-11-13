#include "dynamic_wallpaper.hpp"
#include "dynamic_wallpaper_store.hpp"
#include "wallpaper_store.hpp"
#include "include/cef_scheme.h"
#include "include/cef_parser.h"
#include <windows.h>
#include <shobjidl.h>
#include <mutex>

namespace musxi::cef_adapter {
namespace {
using namespace musxi::wallpaper;
std::mutex guard;
fs::path configPath,assetPath;
Json config={{"enabled",false},{"selected",""},{"directories",Json::array()},{"fps",60},{"quality","standard"}};
std::vector<Project> projects;
std::vector<ScanDirectory> scanReports;
ThumbnailCache thumbnails;
unsigned revision=0;
std::map<std::string,Project> sessions;
unsigned serial=0;
bool testing=false;
void save(const Json& next) {
    const auto temp=fs::path(configPath.wstring()+L".tmp");
    {std::ofstream output(temp,std::ios::binary|std::ios::trunc);output<<next.dump(2);output.close();if(!output)throw std::runtime_error("无法保存动态壁纸设置");}
    if(!MoveFileExW(temp.c_str(),configPath.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("无法保存动态壁纸设置");
    config=next;
}
std::vector<fs::path> roots() {
    std::vector<fs::path> result;
    wchar_t steamPath[32768]{};DWORD size=sizeof(steamPath);
    if(RegGetValueW(HKEY_CURRENT_USER,L"Software\\Valve\\Steam",L"SteamPath",RRF_RT_REG_SZ,nullptr,steamPath,&size)==ERROR_SUCCESS) {
        for(const auto& library:steamLibraries(steamPath)) {
            auto add=[&](const fs::path& path){std::error_code ec;if(fs::is_directory(path,ec))result.push_back(path);};
            add(library/L"steamapps/workshop/content/431960");
            for(const auto* folder:{L"defaultprojects",L"myprojects"})add(library/L"steamapps/common/wallpaper_engine/projects"/folder);
        }
    }
    for(const auto& value:config.at("directories")) {
        const auto expanded=wallpaperRoots(fs::u8path(value.get<std::string>()));result.insert(result.end(),expanded.begin(),expanded.end());
    }
    std::set<fs::path> seen;
    result.erase(std::remove_if(result.begin(),result.end(),[&](const fs::path& path){std::error_code ec;auto key=fs::weakly_canonical(path,ec);return !seen.insert(ec?path:key).second;}),result.end());
    return result;
}
Json state() {return config;}
Json listing() {
    Json items=Json::array();
    for(const auto& p:projects)items.push_back({{"id",p.id},{"title",p.title},{"type",p.type},
        {"preview",p.preview.empty()?"":std::string(WallpaperOrigin)+"/thumbnail/"+p.id+"?v="+std::to_string(revision)},
        {"source",utf8(p.directory.parent_path())},
        {"selected",utf8(p.directory)==config.at("selected").get<std::string>()}});
    Json reports=Json::array();
    for(const auto& r:scanReports)reports.push_back({{"directory",utf8(r.directory)},{"count",r.count},{"invalid",r.invalid},{"unsupported",r.unsupported},{"missing",r.missing},{"error",r.error}});
    return {{"items",items},{"directories",config.at("directories")},{"reports",reports}};
}
std::string chooseDirectory(HWND owner) {
    IFileOpenDialog* dialog=nullptr;auto hr=CoCreateInstance(CLSID_FileOpenDialog,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&dialog));
    fs::path selected;
    if(SUCCEEDED(hr)) {
        FILEOPENDIALOGOPTIONS flags{};hr=dialog->GetOptions(&flags);
        if(SUCCEEDED(hr))hr=dialog->SetOptions(flags|FOS_PICKFOLDERS|FOS_FORCEFILESYSTEM|FOS_PATHMUSTEXIST|FOS_NOCHANGEDIR);
        if(SUCCEEDED(hr))hr=dialog->SetTitle(L"选择壁纸项目或壁纸库目录");
        if(SUCCEEDED(hr))hr=dialog->Show(owner);
        if(SUCCEEDED(hr)) {
            IShellItem* item=nullptr;hr=dialog->GetResult(&item);
            if(SUCCEEDED(hr)){PWSTR path=nullptr;hr=item->GetDisplayName(SIGDN_FILESYSPATH,&path);if(SUCCEEDED(hr))selected=path;CoTaskMemFree(path);item->Release();}
        }
        dialog->Release();
    }
    if(hr==HRESULT_FROM_WIN32(ERROR_CANCELLED))return {};
    if(FAILED(hr))throw std::runtime_error("无法打开壁纸目录，请检查访问权限");
    return utf8(fs::canonical(selected));
}
std::string mime(const fs::path& path) {
    auto ext=path.extension().wstring();std::transform(ext.begin(),ext.end(),ext.begin(),towlower);
    if(ext==L".html")return "text/html";if(ext==L".js")return "text/javascript";if(ext==L".css")return "text/css";
    if(ext==L".json")return "application/json";if(ext==L".png")return "image/png";
    if(ext==L".jpg" || ext==L".jpeg")return "image/jpeg";if(ext==L".webp")return "image/webp";if(ext==L".gif")return "image/gif";
    if(ext==L".mp4" || ext==L".m4v")return "video/mp4";if(ext==L".webm")return "video/webm";
    if(ext==L".ogg")return "audio/ogg";if(ext==L".mp3")return "audio/mpeg";return "application/octet-stream";
}
class Resource final:public CefResourceHandler {
    std::ifstream stream_;int64_t length_=0;std::string mime_;int status_=404;
    std::vector<unsigned char> bytes_;size_t position_=0;
public:
    explicit Resource(Image image):length_(static_cast<int64_t>(image.bytes.size())),mime_(std::move(image.mime)),status_(200),bytes_(std::move(image.bytes)){}
    explicit Resource(const fs::path& path) {
        if(path.empty())return;
        stream_.open(path,std::ios::binary|std::ios::ate);
        if(stream_){length_=static_cast<int64_t>(stream_.tellg());stream_.seekg(0);status_=200;mime_=mime(path);}
    }
    bool Open(CefRefPtr<CefRequest> request,bool& handle,CefRefPtr<CefCallback>)override {handle=true;if(request->GetMethod()!="GET" && request->GetMethod()!="HEAD")status_=405;return true;}
    void GetResponseHeaders(CefRefPtr<CefResponse> response,int64_t& length,CefString&)override {
        response->SetStatus(status_);response->SetMimeType(mime_);length=status_==200?length_:0;
        response->SetHeaderByName("Accept-Ranges","bytes",true);
        response->SetHeaderByName("Cache-Control","no-store",true);
        response->SetHeaderByName("X-Content-Type-Options","nosniff",true);
        response->SetHeaderByName("Content-Security-Policy","default-src 'none'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; font-src 'self' blob: data:; worker-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'",true);
    }
    bool Skip(int64_t bytes,int64_t& skipped,CefRefPtr<CefResourceSkipCallback>)override {
        if(!bytes_.empty()) {
            if(bytes<0 || bytes>length_-static_cast<int64_t>(position_)){skipped=-2;return false;}
            position_+=static_cast<size_t>(bytes);skipped=bytes;return true;
        }
        const auto position=static_cast<int64_t>(stream_.tellg());if(bytes<0 || bytes>length_-position){skipped=-2;return false;}
        stream_.seekg(bytes,std::ios::cur);skipped=bytes;return !!stream_;
    }
    bool Read(void* output,int bytes,int& read,CefRefPtr<CefResourceReadCallback>)override {
        if(status_!=200){read=0;return false;}
        if(!bytes_.empty()) {
            read=static_cast<int>(std::min(static_cast<size_t>(bytes),bytes_.size()-position_));
            std::copy_n(bytes_.data()+position_,read,static_cast<unsigned char*>(output));position_+=read;return read>0;
        }
        stream_.read(static_cast<char*>(output),bytes);read=static_cast<int>(stream_.gcount());return read>0;
    }
    void Cancel()override {stream_.close();}
    IMPLEMENT_REFCOUNTING(Resource);
};
class Factory final:public CefSchemeHandlerFactory {
    CefRefPtr<CefResourceHandler> Create(CefRefPtr<CefBrowser>,CefRefPtr<CefFrame>,const CefString&,CefRefPtr<CefRequest> request)override {
        std::unique_lock lock(guard);fs::path path;
        const auto url=request->GetURL().ToString();
        if(url.rfind(std::string(WallpaperOrigin)+"/",0)!=0)return new Resource(fs::path{});
        auto relative=url.substr(std::string(WallpaperOrigin).size()+1);relative=relative.substr(0,relative.find('?'));
        if(relative=="renderer.js" || relative=="renderer.css")path=containedFile(assetPath,relative);
        else if(relative.rfind("thumbnail/",0)==0 && config.at("enabled")==true) {
            const auto id=relative.substr(10);for(const auto& p:projects)if(p.id==id)path=containedFile(p.directory,p.preview);
            lock.unlock();
            if(!path.empty())try{
                auto image=thumbnails.get(path,thumbnailCopy);lock.lock();
                if(config.at("enabled")==true)return new Resource(std::move(image));
                thumbnails.clear();path.clear();
            }catch(const std::exception&){path.clear();}
        } else if(relative.rfind("session-",0)==0 && config.at("enabled")==true) {
            const auto slash=relative.find('/');const auto found=sessions.find(relative.substr(0,slash));
            if(found!=sessions.end() && slash!=std::string::npos) {
                const auto name=relative.substr(slash+1);
                if(name=="player.html")path=containedFile(assetPath,"player.html");
                else if(name.rfind("assets/",0)==0)path=containedFile(found->second.directory,CefURIDecode(name.substr(7),false,static_cast<cef_uri_unescape_rule_t>(UU_SPACES|UU_URL_SPECIAL_CHARS_EXCEPT_PATH_SEPARATORS|UU_PATH_SEPARATORS)).ToString());
            }
        }
        return new Resource(path);
    }
    IMPLEMENT_REFCOUNTING(Factory);
};
}
bool wallpaperFrameUrl(const std::string& url) {
    const auto prefix=std::string(WallpaperOrigin)+"/session-";
    if(url.rfind(prefix,0)!=0)return false;
    const auto end=url.find("/player.html",prefix.size());
    return end!=std::string::npos && end+12==url.size() && end>prefix.size()
        && url.find_first_not_of("0123456789",prefix.size())==end;
}
void initDynamicWallpaper(const fs::path& profile,const fs::path& assets,bool smoke) {
    testing=smoke;fs::create_directories(profile);
    configPath=profile/L"dynamic-wallpaper.json";assetPath=assets;
    const auto saved=readProject(configPath);
    if(!testing && saved.is_object() && saved.contains("enabled") && saved.at("enabled").is_boolean() && saved.contains("selected") && saved.at("selected").is_string()
        && saved.contains("directories") && saved.at("directories").is_array()
        && std::all_of(saved.at("directories").begin(),saved.at("directories").end(),[](const Json& v){return v.is_string();})) {
        config=saved;
        if(!config.contains("fps") || !config.at("fps").is_number_integer() || (config.at("fps")!=30 && config.at("fps")!=60))config["fps"]=60;
        if(!config.contains("quality") || !config.at("quality").is_string() || (config.at("quality")!="standard" && config.at("quality")!="high"))config["quality"]="standard";
    }
    CefRegisterSchemeHandlerFactory("https","wallpaper.musxi.invalid",new Factory);
}
musxi::LibraryReply dynamicWallpaperCommand(const std::string& command,const std::string& params,void* owner) {
    try {
        const auto data=Json::parse(params);std::string directory;
        if(command=="dynamicWallpaper.addDirectory" && data.empty())directory=chooseDirectory(static_cast<HWND>(owner));
        std::unique_lock lock(guard);
        if(command=="dynamicWallpaper.getState" && data.empty())return {0,{},state().dump()};
        if(testing && command=="dynamicWallpaper.testFixtures" && data.empty()) {
            auto next=config;next["directories"]=Json::array({utf8(assetPath.parent_path().parent_path()/L"dynamic-wallpaper-fixtures")});save(next);return {0,{},state().dump()};
        }
        if(command=="dynamicWallpaper.setEnabled" && data.size()==1 && data.contains("enabled") && data.at("enabled").is_boolean()) {
            auto next=config;next["enabled"]=data.at("enabled");save(next);
            if(!next.at("enabled").get<bool>()){sessions.clear();projects.clear();scanReports.clear();thumbnails.clear();}
            return {0,{},state().dump()};
        }
        if(!config.at("enabled").get<bool>())return {409,"请先启用动态壁纸扩展",{}};
        if(command=="dynamicWallpaper.setPlayback" && data.size()==2 && data.contains("fps") && data.at("fps").is_number_integer()
            && (data.at("fps")==30 || data.at("fps")==60) && data.contains("quality") && data.at("quality").is_string()
            && (data.at("quality")=="standard" || data.at("quality")=="high")) {
            auto next=config;next["fps"]=data.at("fps");next["quality"]=data.at("quality");save(next);return {0,{},state().dump()};
        }
        if(command=="dynamicWallpaper.addDirectory" && data.empty()) {
            if(!directory.empty()) {auto next=config;auto& dirs=next["directories"];if(std::find(dirs.begin(),dirs.end(),directory)==dirs.end())dirs.push_back(directory);save(next);}
            return {0,{},state().dump()};
        }
        if(command=="dynamicWallpaper.removeDirectory" && data.size()==1 && data.contains("directory") && data.at("directory").is_string()) {
            auto next=config;auto& dirs=next["directories"];dirs.erase(std::remove(dirs.begin(),dirs.end(),data.at("directory")),dirs.end());save(next);return {0,{},state().dump()};
        }
        if(command=="dynamicWallpaper.scan" && data.empty()) {
            const auto directories=roots();const auto snapshot=config;lock.unlock();
            std::vector<ScanDirectory> reports;auto incoming=scanProjects(directories,&reports);lock.lock();
            if(config!=snapshot)return {409,"动态壁纸设置已变化，请重试",{}};
            for(auto& p:incoming) {
                const auto previous=std::find_if(projects.begin(),projects.end(),[&](const Project& old){return old.directory==p.directory;});
                p.id=previous!=projects.end()?previous->id:std::to_string(++serial);
            }
            projects=std::move(incoming);scanReports=std::move(reports);++revision;return {0,{},listing().dump()};
        }
        if((command=="dynamicWallpaper.open" || command=="dynamicWallpaper.apply") && data.size()==1 && data.contains("id") && data.at("id").is_string()) {
            const auto found=std::find_if(projects.begin(),projects.end(),[&](const Project& p){return p.id==data.at("id").get<std::string>();});
            if(found==projects.end() || !fs::exists(found->directory/L"project.json"))return {404,"壁纸资源已丢失，请刷新列表",{}};
            if(command=="dynamicWallpaper.apply"){auto next=config;next["selected"]=utf8(found->directory);save(next);return {0,{},state().dump()};}
            const auto token="session-"+std::to_string(++serial);sessions[token]=*found;
            return {0,{},Json{{"url",std::string(WallpaperOrigin)+"/"+token+"/player.html"},{"type",found->type},{"file",found->file}}.dump()};
        }
        if(command=="dynamicWallpaper.release" && data.size()==1 && data.contains("url") && data.at("url").is_string()) {
            const auto url=data.at("url").get<std::string>();if(!wallpaperFrameUrl(url))return {400,"无效壁纸会话",{}};
            const auto prefix=std::string(WallpaperOrigin).size()+1;sessions.erase(url.substr(prefix,url.find('/',prefix)-prefix));return {0,{},"true"};
        }
        return {400,"无效动态壁纸操作",{}};
    } catch(const std::exception&) {return {500,"动态壁纸操作失败，请检查资源目录和文件权限",{}};}
}
}
