#include "../src/application/application.hpp"
#include <iostream>
int main() {
    // A C++20 consumer links to the actual C++17 application and core archives.
    // No CEF SDK is required for this boundary test.
    const auto state=musxi::applicationPlayerState();
    if(state.opened || state.playing || !state.trackId.empty()) return 1;
    std::cout << "C++20 consumer -> C++17 application/core: PASS\n";
}
