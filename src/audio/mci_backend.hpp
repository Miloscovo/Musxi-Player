#pragma once
#include "audio_backend.hpp"
#include <memory>

namespace musxi {
// Concrete implementation and its notification window stay inside the adapter.
std::unique_ptr<IAudioBackend> makeMciAudioBackend();
}
