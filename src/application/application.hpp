#pragma once
#include "../player/player_service.hpp"
namespace musxi {
// C++17 boundary: ownership stays in the application; no CEF types exposed.
int runNativeApplication(void* instance, int show);
// Application-thread only; returns a copy, never a borrowed backend object.
PlayerState applicationPlayerState();
}
