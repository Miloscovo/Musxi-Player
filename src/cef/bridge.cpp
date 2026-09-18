#include "include/cef_app.h"
#include "../application/application.hpp"
#include "../player/player_service.hpp"

namespace musxi::cef_adapter {
// Called only after transport has marshalled onto the native application thread.
// No CEF object enters the application service API.
PlayerState readStateOnApplicationThread() {
    return applicationPlayerState();
}
}
