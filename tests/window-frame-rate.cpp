#include "../src/cef/window_frame_rate.hpp"
#include <stdexcept>
int main() {
    using musxi::cef_adapter::windowFrameRate;
    if(windowFrameRate(0)!=60 || windowFrameRate(1)!=60 || windowFrameRate(30)!=30 ||
       windowFrameRate(60)!=60 || windowFrameRate(90)!=90 || windowFrameRate(120)!=120 ||
       windowFrameRate(144)!=120 || windowFrameRate(240)!=120)
        throw std::runtime_error("Window frame rate selection regression");
}
