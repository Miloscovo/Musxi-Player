#pragma once
#include "../third_party/json.hpp"
#include <wincrypt.h>
#include <future>
#include <thread>
#include <atomic>
#include <chrono>

using Json = nlohmann::json;
namespace cloud {
inline std::wstring toWide(const std::string& value) {
    int n = MultiByteToWideChar(CP_UTF8, 0, value.data(), (int)value.size(), nullptr, 0);
    std::wstring out(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), (int)value.size(), out.data(), n); return out;
}
inline std::filesystem::path dataDir() {
    wchar_t p[MAX_PATH]{};
    if (FAILED(SHGetFolderPathW(nullptr, CSIDL_LOCAL_APPDATA, nullptr, 0, p))) throw std::runtime_error("Cannot find application data directory");
    auto dir = std::filesystem::path(p) / L"MintPlayer";
    std::filesystem::create_directories(dir); return dir;
}
inline Json readSession() {
    try {
        std::ifstream file(dataDir() / L"kugou-session.dat", std::ios::binary);
        std::vector<char> bytes((std::istreambuf_iterator<char>(file)), {});
        if (bytes.empty() || bytes.size() > 1024 * 1024) return nullptr;
        DATA_BLOB in{(DWORD)bytes.size(), reinterpret_cast<BYTE*>(bytes.data())}, out{};
        if (!CryptUnprotectData(&in, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out)) return nullptr;
        std::string text(reinterpret_cast<char*>(out.pbData), out.cbData);
        SecureZeroMemory(out.pbData, out.cbData); LocalFree(out.pbData);
        auto json = Json::parse(text, nullptr, false); SecureZeroMemory(text.data(), text.size());
        return json.is_discarded() ? Json(nullptr) : json;
    } catch (...) { return nullptr; }
}
inline bool saveSession(const Json& value) {
    try {
        auto path = dataDir() / L"kugou-session.dat";
        auto text = value.dump(); DATA_BLOB in{(DWORD)text.size(), reinterpret_cast<BYTE*>(text.data())}, out{};
        bool ok = CryptProtectData(&in, L"MintPlayer KuGou lite", nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out);
        SecureZeroMemory(text.data(), text.size()); if (!ok) return false;
        auto temp = path; temp += L".tmp";
        std::ofstream file(temp, std::ios::binary | std::ios::trunc);
        file.write(reinterpret_cast<char*>(out.pbData), out.cbData); file.close(); LocalFree(out.pbData);
        return !file.fail() && MoveFileExW(temp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
    } catch (...) { return false; }
}
inline void forgetSession() { std::error_code ec; std::filesystem::remove(dataDir() / L"kugou-session.dat", ec); }

// One in-flight request per bridge. The UI polls its future without blocking.
class Bridge {
    HANDLE input = nullptr, output = nullptr;
    std::atomic<HANDLE> process{nullptr};
    std::atomic<bool> stopping{false};
public:
    ~Bridge() { stop(); }
    bool running() const { return process.load() != nullptr; }
    bool start(std::wstring& error) {
        if (running()) return true;
        stopping = false;
        wchar_t exe[32768]{}; GetModuleFileNameW(nullptr, exe, 32768);
        auto base = std::filesystem::path(exe).parent_path();
        auto service = base / L"services" / L"bridge.cjs";
        if (!std::filesystem::exists(service)) service = base.parent_path() / L"services" / L"bridge.cjs";
        if (!std::filesystem::exists(service)) { error = L"缺少酷狗接口服务，请运行 setup-cloud.ps1"; return false; }
        auto node = base / L"runtime" / L"node.exe";
        if (!std::filesystem::exists(node)) {
            wchar_t found[32768]{};
            if (!SearchPathW(nullptr, L"node.exe", nullptr, 32768, found, nullptr)) { error = L"请先运行 setup-cloud.ps1 安装本机接口运行环境"; return false; }
            node = found;
        }
        SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE}; HANDLE childIn = nullptr, childOut = nullptr;
        if (!CreatePipe(&childIn, &input, &sa, 0) || !CreatePipe(&output, &childOut, &sa, 0)) {
            if (childIn) CloseHandle(childIn);
            if (childOut) CloseHandle(childOut);
            stop(); error = L"无法建立本机接口连接"; return false;
        }
        SetHandleInformation(input, HANDLE_FLAG_INHERIT, 0); SetHandleInformation(output, HANDLE_FLAG_INHERIT, 0);
        HANDLE nullHandle = CreateFileW(L"NUL", GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, &sa, OPEN_EXISTING, 0, nullptr);
        STARTUPINFOW si{}; si.cb = sizeof(si); si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW; si.wShowWindow = SW_HIDE;
        si.hStdInput = childIn; si.hStdOutput = childOut; si.hStdError = nullHandle;
        PROCESS_INFORMATION pi{};
        std::wstring cmd = L"\"" + node.wstring() + L"\" \"" + service.wstring() + L"\"";
        bool ok = CreateProcessW(node.c_str(), cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr, service.parent_path().c_str(), &si, &pi);
        CloseHandle(childIn); CloseHandle(childOut); if (nullHandle != INVALID_HANDLE_VALUE) CloseHandle(nullHandle);
        if (!ok) { stop(); error = L"酷狗接口进程启动失败"; return false; }
        CloseHandle(pi.hThread); process = pi.hProcess; return true;
    }
    Json call(const Json& request) {
        try {
            auto line = request.dump() + "\n"; DWORD wrote = 0;
            if (stopping || !WriteFile(input, line.data(), (DWORD)line.size(), &wrote, nullptr) || wrote != line.size()) throw std::runtime_error("pipe");
            SecureZeroMemory(line.data(), line.size());
            std::string result; auto deadline = GetTickCount64() + 120000;
            while (!stopping && GetTickCount64() < deadline) {
                DWORD available = 0;
                if (!PeekNamedPipe(output, nullptr, 0, nullptr, &available, nullptr)) throw std::runtime_error("pipe");
                if (available) {
                    char buf[8192]; DWORD got = 0;
                    if (!ReadFile(output, buf, std::min<DWORD>(available, sizeof(buf)), &got, nullptr)) throw std::runtime_error("pipe");
                    result.append(buf, got);
                    if (result.size() > 32 * 1024 * 1024) throw std::runtime_error("size");
                    auto end = result.find('\n');
                    if (end != std::string::npos) return Json::parse(result.substr(0, end));
                } else {
                    auto handle = process.load();
                    if (!handle || WaitForSingleObject(handle, 0) == WAIT_OBJECT_0) throw std::runtime_error("exit");
                    std::this_thread::sleep_for(std::chrono::milliseconds(30));
                }
            }
            return {{"ok", false}, {"error", "请求超时，请退出账号连接后重试"}, {"fatal", true}};
        } catch (...) { return {{"ok", false}, {"error", "本机接口连接已中断，请重新连接"}, {"fatal", true}}; }
    }
    void cancel() { stopping = true; auto p = process.load(); if (p) TerminateProcess(p, 0); }
    // Call stop only after the request future has completed.
    void stop() {
        cancel(); auto p = process.exchange(nullptr);
        if (p) { WaitForSingleObject(p, 1000); CloseHandle(p); }
        if (input) { CloseHandle(input); input = nullptr; }
        if (output) { CloseHandle(output); output = nullptr; }
    }
};
}
