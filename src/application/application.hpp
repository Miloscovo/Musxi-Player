#pragma once
#include "../player/player_service.hpp"
namespace musxi {
// C++17 boundary: ownership stays in the application; no CEF types exposed.
int runNativeApplication(void* instance, int show);
// Optional host integration, called only on the native UI thread.
// Install before runNativeApplication; the host owns callback lifetime.
struct HostHooks {
    void (*ready)(void* window)=nullptr;
    void (*tick)()=nullptr;
    bool (*canClose)()=nullptr;
    bool connectCloud=true; // Disable network startup for host smoke tests only.
};
void setHostHooks(HostHooks hooks);
// Application-thread only; returns a copy, never a borrowed backend object.
PlayerState applicationPlayerState();
PlayerResult applicationPlayerCommand(PlayerCommand command, std::uint32_t value=0);
// Single host-owned observer. Clear before destroying the host; callbacks must not reenter.
void setApplicationPlayerEvents(PlayerService::Events sink);
}
