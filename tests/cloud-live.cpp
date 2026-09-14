// Read-only live integration probe; never prints QR keys or credentials.
#include "../src/main.cpp"
#include <iostream>
int wmain() {
    GdiplusStartupInput input;ULONG_PTR token;GdiplusStartup(&token,&input,nullptr);
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    int code=0;
    try {
        cloud::Bridge bridge;std::wstring error;
        if(!bridge.start(error)) throw std::runtime_error("start failed");
        auto init=bridge.call({{"op","init"}});
        if(!init.value("ok",false)) throw std::runtime_error("init failed");
        auto qr=bridge.call({{"op","qr"}});
        if(!qr.value("ok",false)) throw std::runtime_error("QR request failed");
        loadQr(qr.at("data").at("image").get<std::string>());
        if(!cloudQr) throw std::runtime_error("native QR image decode failed");
        auto poll=bridge.call({{"op","poll"}});
        if(!poll.value("ok",false)) throw std::runtime_error("QR status request failed");
        std::cout<<"PASS native child-process IPC, live QR generation, GDI+ decoding, QR status polling\n";
        bridge.stop();clearQr();
    } catch(const std::exception& e) {std::cerr<<"FAIL "<<e.what()<<'\n';code=1;}
    CoUninitialize();GdiplusShutdown(token);return code;
}
