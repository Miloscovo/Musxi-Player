#include "../src/application/application.hpp"
#include <windows.h>
#include <iostream>

namespace {
HWND window=nullptr;
bool consumedQuit=false;
void ready(void* handle) {window=static_cast<HWND>(handle);}
void tick() {
    SendMessageW(window,WM_CLOSE,0,0);
    // Simulate CEF's nested pump taking the application's exit message.
    MSG message{};
    consumedQuit=PeekMessageW(&message,nullptr,WM_QUIT,WM_QUIT,PM_REMOVE)!=0;
}
}
int main() {
    musxi::HostHooks hooks{};
    hooks.ready=ready;hooks.tick=tick;
    hooks.connectCloud=false;hooks.testProfile=true;
    musxi::setHostHooks(hooks);
    if(musxi::runApplication(GetModuleHandleW(nullptr))!=0 || !consumedQuit || IsWindow(window))return 1;
    std::cout << "Window destruction exits even when a nested pump consumes WM_QUIT: PASS\n";
}
