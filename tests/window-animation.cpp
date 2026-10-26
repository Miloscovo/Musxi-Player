#include "../src/cef/window_animation.hpp"
#include <cmath>
#include <stdexcept>
int main() {
    using namespace musxi::cef_adapter;
    const auto check=[](bool value){if(!value)throw std::runtime_error("Window easing regression");};
    check(windowTransitionMilliseconds==220);
    check(windowTransitionProgress(-1)==0 && windowTransitionProgress(0)==0);
    check(windowTransitionProgress(1)==1 && windowTransitionProgress(2)==1);
    check(std::abs(windowTransitionProgress(.3425)-.875)<.000001);
    double previous=0;
    for(int i=1;i<=100;++i) {
        const auto value=windowTransitionProgress(i/100.);
        check(value>=previous && value<=1);previous=value;
    }
}
