#pragma once

namespace musxi::cef_adapter {
constexpr int windowFrameRate(unsigned refreshRate) {
    return refreshRate <= 1 ? 60 : refreshRate > 120 ? 120 : static_cast<int>(refreshRate);
}
}
