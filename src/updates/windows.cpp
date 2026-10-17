#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <winhttp.h>
#include <shellapi.h>
#include "updates.hpp"
#include <algorithm>
#include <memory>
#include <regex>

namespace musxi::updates {
namespace {
struct Close {void operator()(void* h)const{if(h)WinHttpCloseHandle(h);}};
using Handle=std::unique_ptr<void,Close>;
// The final HANDLE_CLOSING callback owns a reference. Cancellation can return
// immediately without freeing an outstanding read buffer under WinHTTP.
struct Context {
    std::atomic_int refs{1};
    HANDLE event=CreateEventW(nullptr,FALSE,FALSE,nullptr);
    DWORD error=0,read=0;
    char buffer[16384]{};
    void release(){if(--refs==0){if(event)CloseHandle(event);delete this;}}
};
void CALLBACK completed(HINTERNET,DWORD_PTR value,DWORD status,void* data,DWORD length) {
    auto c=reinterpret_cast<Context*>(value);if(!c)return;
    if(status==WINHTTP_CALLBACK_STATUS_HANDLE_CLOSING){c->release();return;}
    if(status==WINHTTP_CALLBACK_STATUS_REQUEST_ERROR) {
        c->error=static_cast<WINHTTP_ASYNC_RESULT*>(data)->dwError;SetEvent(c->event);
    }else if(status==WINHTTP_CALLBACK_STATUS_READ_COMPLETE) {
        c->read=length;SetEvent(c->event);
    }else if(status==WINHTTP_CALLBACK_STATUS_SENDREQUEST_COMPLETE || status==WINHTTP_CALLBACK_STATUS_HEADERS_AVAILABLE)SetEvent(c->event);
}
struct Request {
    Context* context=new Context;
    Handle handle;
    ~Request(){handle.reset();context->release();}
    bool prepare(HINTERNET raw) {
        handle.reset(raw);if(!handle || !context->event)return false;
        DWORD_PTR value=reinterpret_cast<DWORD_PTR>(context);
        if(!WinHttpSetOption(handle.get(),WINHTTP_OPTION_CONTEXT_VALUE,&value,sizeof(value)))return false;
        if(WinHttpSetStatusCallback(handle.get(),completed,WINHTTP_CALLBACK_FLAG_ALL_COMPLETIONS|WINHTTP_CALLBACK_FLAG_HANDLES,0)==WINHTTP_INVALID_STATUS_CALLBACK)return false;
        ++context->refs;return true;
    }
};
std::int64_t secondsNow(){return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();}
std::wstring header(HINTERNET request,const wchar_t* name) {
    wchar_t value[128]{};DWORD size=sizeof(value);
    if(!WinHttpQueryHeaders(request,WINHTTP_QUERY_CUSTOM,name,value,&size,WINHTTP_NO_HEADER_INDEX))
        return GetLastError()==ERROR_INSUFFICIENT_BUFFER?L"header-too-large":L"";
    return value;
}
}
FetchResult fetchReleases(const std::atomic_bool& cancelled) {
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(12);
    const auto expired=[&]{return cancelled.load() || std::chrono::steady_clock::now()>=deadline;};
    const auto failure=[&]() -> FetchResult {
        return {{},cancelled?"cancelled":GetLastError()==ERROR_WINHTTP_TIMEOUT || std::chrono::steady_clock::now()>=deadline?"timeout":"network_error",0};
    };
    if(expired())return failure();
    Handle session(WinHttpOpen(L"MusxiPlayer-UpdateCheck/1",WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,WINHTTP_NO_PROXY_NAME,WINHTTP_NO_PROXY_BYPASS,WINHTTP_FLAG_ASYNC));
    if(!session)return failure();
    if(!WinHttpSetTimeouts(session.get(),1500,1500,1500,1500))return failure();
    Handle connection(WinHttpConnect(session.get(),L"api.github.com",INTERNET_DEFAULT_HTTPS_PORT,0));
    if(!connection || expired())return failure();
    Request operation;
    if(!operation.prepare(WinHttpOpenRequest(connection.get(),L"GET",L"/repos/Miloscovo/Musxi-Player/releases?per_page=100",nullptr,
        WINHTTP_NO_REFERER,WINHTTP_DEFAULT_ACCEPT_TYPES,WINHTTP_FLAG_SECURE)))return failure();
    const auto request=operation.handle.get();
    const auto wait=[&](BOOL initiated) {
        if(!initiated && GetLastError()!=ERROR_IO_PENDING)return false;
        while(!expired()) {
            const auto result=WaitForSingleObject(operation.context->event,50);
            if(result==WAIT_OBJECT_0) {
                if(operation.context->error){SetLastError(operation.context->error);return false;}
                return true;
            }
            if(result!=WAIT_TIMEOUT)return false;
        }
        return false;
    };
    DWORD disable=WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_COOKIES;
    if(!WinHttpSetOption(request,WINHTTP_OPTION_DISABLE_FEATURE,&disable,sizeof(disable)))return failure();
    // An update check is public. Never automatically send Windows credentials.
    DWORD auth=WINHTTP_AUTOLOGON_SECURITY_LEVEL_HIGH;
    if(!WinHttpSetOption(request,WINHTTP_OPTION_AUTOLOGON_POLICY,&auth,sizeof(auth)))return failure();
    constexpr wchar_t headers[]=L"Accept: application/vnd.github+json\r\nX-GitHub-Api-Version: 2022-11-28\r\n";
    if(expired() || !wait(WinHttpSendRequest(request,headers,static_cast<DWORD>(-1),WINHTTP_NO_REQUEST_DATA,0,0,
        reinterpret_cast<DWORD_PTR>(operation.context))) || expired() || !wait(WinHttpReceiveResponse(request,nullptr)))return failure();
    DWORD status=0,size=sizeof(status);
    if(!WinHttpQueryHeaders(request,WINHTTP_QUERY_STATUS_CODE|WINHTTP_QUERY_FLAG_NUMBER,WINHTTP_HEADER_NAME_BY_INDEX,&status,&size,WINHTTP_NO_HEADER_INDEX))return failure();
    if(status==429 || (status==403 && header(request,L"X-RateLimit-Remaining")==L"0") ||
        (status==403 && !header(request,L"Retry-After").empty())) {
        auto retry=secondsNow()+3600;
        try {
            auto value=header(request,L"Retry-After");
            if(!value.empty() && value.find_first_not_of(L"0123456789")==value.npos)retry=secondsNow()+std::min<std::int64_t>(86400,std::stoll(value));
            else {value=header(request,L"X-RateLimit-Reset");if(!value.empty())retry=std::stoll(value);}
        }catch(...){}
        return {{},"rate_limited",retry};
    }
    if(status!=200)return {{},"http_error",0};
    // Refuse a partial list: guessing from only the first page can offer a wrong version.
    if(!header(request,L"Link").empty())return {{},"release_list_incomplete",0};
    std::string body;
    for(;;) {
        if(expired())return failure();
        if(!wait(WinHttpReadData(request,operation.context->buffer,sizeof(operation.context->buffer),nullptr)))return failure();
        const auto read=operation.context->read;
        if(!read)break;
        if(body.size()+read>1024*1024)return {{},"response_too_large",0};
        body.append(operation.context->buffer,read);
    }
    if(expired())return failure();
    return {std::move(body),{},0};
}
bool openBrowser(const std::string& url) {
    // Defense in depth, including calls from future native callers.
    static const std::regex allowed(R"(^https://github\.com/Miloscovo/Musxi-Player/releases/(tag/[v0-9A-Za-z.+-]+|download/[v0-9A-Za-z.+-]+/MusxiPlayer-[0-9A-Za-z.+-]+\.exe)$)");
    if(url.size()>512 || !std::regex_match(url,allowed))return false;
    std::wstring wide(url.begin(),url.end());
    return reinterpret_cast<INT_PTR>(ShellExecuteW(nullptr,L"open",wide.c_str(),nullptr,nullptr,SW_SHOWNORMAL))>32;
}
}
