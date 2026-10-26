#pragma once

namespace musxi::cef_adapter {
inline constexpr unsigned windowTransitionMilliseconds=220;
// Match the sidebar's cubic-bezier(.22,1,.36,1), solving time on its x axis.
inline double windowTransitionProgress(double progress) {
    if(progress<=0)return 0;
    if(progress>=1)return 1;
    double low=0,high=1;
    for(int i=0;i<24;++i) {
        const double t=(low+high)/2,u=1-t;
        if(3*u*u*t*.22+3*u*t*t*.36+t*t*t<progress)low=t;else high=t;
    }
    const double u=1-(low+high)/2;
    return 1-u*u*u;
}
}
