#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <bcrypt.h>
#include "download.hpp"
#include <array>
#include <vector>

namespace musxi::updates {
namespace {
struct Handle {
    HANDLE value=INVALID_HANDLE_VALUE;
    ~Handle(){if(value!=INVALID_HANDLE_VALUE)CloseHandle(value);}
};
struct Hash {
    BCRYPT_ALG_HANDLE algorithm=nullptr;
    BCRYPT_HASH_HANDLE hash=nullptr;
    std::vector<unsigned char> buffer;
    Hash() {
        if(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)<0)throw std::runtime_error("verification_failed");
        DWORD size=0,read=0;
        if(BCryptGetProperty(algorithm,BCRYPT_OBJECT_LENGTH,reinterpret_cast<PUCHAR>(&size),sizeof(size),&read,0)<0) {
            BCryptCloseAlgorithmProvider(algorithm,0);algorithm=nullptr;throw std::runtime_error("verification_failed");
        }
        buffer.resize(size);
        if(BCryptCreateHash(algorithm,&hash,buffer.data(),size,nullptr,0,0)<0) {
            BCryptCloseAlgorithmProvider(algorithm,0);algorithm=nullptr;throw std::runtime_error("verification_failed");
        }
    }
    ~Hash(){if(hash)BCryptDestroyHash(hash);if(algorithm)BCryptCloseAlgorithmProvider(algorithm,0);}
    void add(const char* data,std::size_t length) {
        if(length>MAXDWORD || BCryptHashData(hash,reinterpret_cast<PUCHAR>(const_cast<char*>(data)),static_cast<ULONG>(length),0)<0)
            throw std::runtime_error("verification_failed");
    }
    std::string finish() {
        std::array<unsigned char,32> bytes{};
        if(BCryptFinishHash(hash,bytes.data(),static_cast<ULONG>(bytes.size()),0)<0)throw std::runtime_error("verification_failed");
        std::string result;constexpr char hex[]="0123456789abcdef";
        for(auto b:bytes){result+=hex[b>>4];result+=hex[b&15];}return result;
    }
};
// Reject junctions/symlinks before creating or cleaning our own generated jobs.
bool plainDirectory(const std::filesystem::path& path) {
    if(!path.is_absolute())return false;
    auto part=path;
    while(!part.empty()) {
        const auto flags=GetFileAttributesW(part.c_str());
        if(flags==INVALID_FILE_ATTRIBUTES || !(flags&FILE_ATTRIBUTE_DIRECTORY) || (flags&FILE_ATTRIBUTE_REPARSE_POINT))return false;
        const auto parent=part.parent_path();if(parent==part)break;part=parent;
    }
    return true;
}
std::string randomName() {
    std::array<unsigned char,16> bytes{};
    if(BCryptGenRandom(nullptr,bytes.data(),static_cast<ULONG>(bytes.size()),BCRYPT_USE_SYSTEM_PREFERRED_RNG)<0)
        throw std::runtime_error("storage_error");
    constexpr char hex[]="0123456789abcdef";std::string result="job-";
    for(auto b:bytes){result+=hex[b>>4];result+=hex[b&15];}return result;
}
}
struct Downloader::File {
    Handle lock;
    Handle rootLock,jobLock;
    std::filesystem::path path;
};
Downloader::Downloader(std::filesystem::path root,DownloadTransport transport,Launch launch,bool retainLaunched)
    :root_(std::move(root)),transport_(std::move(transport)),launch_(std::move(launch)),retainLaunched_(retainLaunched){}
Downloader::~Downloader(){
    stop();if(!installing() || !retainLaunched_)clear();
    if(!retainLaunched_ && plainDirectory(root_))RemoveDirectoryW(root_.c_str());
}
void Downloader::clear() {
    ready_.reset();
    if(!job_.empty() && plainDirectory(root_) && plainDirectory(job_) && job_.parent_path()==root_) {
        // No recursive deletion of user-supplied/cache paths; only our two files.
        DeleteFileW((job_/L"installer.part").c_str());
        DeleteFileW((job_/L"installer.exe").c_str());
        RemoveDirectoryW(job_.c_str());
    }
    job_.clear();
}
void Downloader::stop() {
    stopped_=true;cancelled_=true;
    if(future_.valid()){try{auto out=future_.get();}catch(...){}}
    if(status_=="downloading"){status_="cancelled";reason_="cancelled";}
}
void Downloader::poll() {
    if(!future_.valid() || future_.wait_for(std::chrono::seconds(0))!=std::future_status::ready)return;
    Outcome out;try{out=future_.get();}catch(...){out.reason="internal_error";}
    reason_=out.reason;ready_=std::move(out.file);
    status_=ready_?"ready":reason_=="cancelled"?"cancelled":"failed";
    if(!ready_)clear();
}
Json Downloader::state() {
    poll();const bool busy=future_.valid();
    const auto phase=phase_.load();
    return {{"status",busy?(phase==0?"preparing":phase==1?"downloading":"verifying"):status_},
        {"reason",reason_},{"releaseId",release_.id},{"assetId",release_.assetId},{"version",release_.version},
        {"receivedBytes",received_.load()},{"totalBytes",release_.bytes},
        {"canCancel",busy && !cancelled_},{"canInstall",!stopped_ && status_=="ready"}};
}
Reply Downloader::start(const Release& r) {
    poll();
    if(stopped_ || installing())return {409,"shutting_down"};
    if(future_.valid())return {409,"download_busy"};
    if(r.assetId.empty() || r.manifestId.empty() || r.manifestBytes==0 || r.manifestBytes>4096)
        return {409,"download_unavailable"};
    if(status_=="ready" && r.id==release_.id && r.assetId==release_.assetId)return {0,{},state()};
    clear();release_=r;reason_.clear();received_=0;phase_=0;cancelled_=false;status_="downloading";
    try {
        if(root_.empty() || !root_.is_absolute())throw std::runtime_error("storage_error");
        std::filesystem::create_directories(root_);
        if(!plainDirectory(root_))throw std::runtime_error("storage_error");
        job_=root_/randomName();
        if(!CreateDirectoryW(job_.c_str(),nullptr))throw std::runtime_error("storage_error");
        future_=std::async(std::launch::async,[this,r]{return run(r);});
    }catch(...){status_="failed";reason_="storage_error";clear();return {500,"storage_error"};}
    return {0,{},state()};
}
Reply Downloader::cancel() {
    poll();
    if(installing())return {409,"shutting_down"};
    if(future_.valid())cancelled_=true;
    else {clear();status_="cancelled";reason_="cancelled";}
    return {0,{},state()};
}
bool Downloader::installing() const{return status_=="installing";}
Reply Downloader::install(const std::string& releaseId,const std::string& assetId) {
    poll();
    if(stopped_ || installing())return {409,"shutting_down"};
    if(releaseId!=release_.id || assetId!=release_.assetId)return {409,"release_changed"};
    if(status_!="ready" || !ready_)return {409,"download_not_ready"};
    // The verified read handle denies all writes/deletion until normal shutdown.
    // No path from the bridge reaches the launcher.
    if(!launch_(ready_->path))return {500,"installer_start_failed"};
    status_="installing";return {0,{},state()};
}
Downloader::Outcome Downloader::run(Release r) {
    try {
        std::string manifest;
        auto reason=transport_(manifestUrl(r),4096,cancelled_,[&](const char* data,std::size_t size) {
            if(cancelled_ || size>4096-manifest.size())return false;
            manifest.append(data,size);return true;
        });
        if(cancelled_)return {"cancelled",{}};
        if(!reason.empty())return {reason,{}};
        if(manifest.size()!=r.manifestBytes)return {"invalid_manifest",{}};
        const auto expected=parseInstallerManifest(manifest,r);
        ULARGE_INTEGER available{};
        if(!GetDiskFreeSpaceExW(job_.c_str(),&available,nullptr,nullptr))return {"storage_error",{}};
        if(available.QuadPart<r.bytes+16*1024*1024)return {"disk_full",{}};
        const auto part=job_/L"installer.part",final=job_/L"installer.exe";
        {
            Handle output;
            output.value=CreateFileW(part.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
            if(output.value==INVALID_HANDLE_VALUE)return {"storage_error",{}};
            phase_=1;std::string writeError;
            reason=transport_(downloadUrl(r),r.bytes,cancelled_,[&](const char* data,std::size_t size) {
                if(cancelled_)return false;
                if(size>r.bytes-received_.load()){writeError="size_mismatch";return false;}
                DWORD written=0;
                if(size>MAXDWORD || !WriteFile(output.value,data,static_cast<DWORD>(size),&written,nullptr) || written!=size) {
                    writeError=GetLastError()==ERROR_DISK_FULL?"disk_full":"storage_error";return false;
                }
                received_+=size;return true;
            });
            if(cancelled_)return {"cancelled",{}};
            if(!writeError.empty())return {writeError,{}};
            if(!reason.empty())return {reason,{}};
            if(received_!=r.bytes)return {"size_mismatch",{}};
            if(!FlushFileBuffers(output.value))return {"storage_error",{}};
        }
        phase_=2;
        if(!MoveFileExW(part.c_str(),final.c_str(),MOVEFILE_WRITE_THROUGH))return {"storage_error",{}};
        auto file=std::make_shared<File>();file->path=final;
        file->rootLock.value=CreateFileW(root_.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
        file->jobLock.value=CreateFileW(job_.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
        if(file->rootLock.value==INVALID_HANDLE_VALUE || file->jobLock.value==INVALID_HANDLE_VALUE || !plainDirectory(job_))
            return {"verification_failed",{}};
        file->lock.value=CreateFileW(final.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
        if(file->lock.value==INVALID_HANDLE_VALUE)return {"storage_error",{}};
        BY_HANDLE_FILE_INFORMATION info{};
        if(!GetFileInformationByHandle(file->lock.value,&info) || (info.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT) ||
            info.nNumberOfLinks!=1 || (static_cast<std::uint64_t>(info.nFileSizeHigh)<<32 | info.nFileSizeLow)!=r.bytes)
            return {"verification_failed",{}};
        Hash hash;std::array<char,65536> buffer{};DWORD read=0;
        for(;;) {
            if(cancelled_)return {"cancelled",{}};
            if(!ReadFile(file->lock.value,buffer.data(),static_cast<DWORD>(buffer.size()),&read,nullptr))return {"storage_error",{}};
            if(!read)break;hash.add(buffer.data(),read);
        }
        if(hash.finish()!=expected.sha256)return {"checksum_mismatch",{}};
        return {{},std::move(file)};
    }catch(const std::exception& e){return {std::string(e.what())=="invalid_manifest"?"invalid_manifest":"internal_error",{}};}
    catch(...){return {"internal_error",{}};}
}
}
