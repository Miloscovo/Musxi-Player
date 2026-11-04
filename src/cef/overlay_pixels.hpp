#pragma once
#include <algorithm>
#include <cstddef>
#include <cstring>

// The caller validates positive frame sizes and owns both BGRA buffers.
inline void copyOverlayPixels(void* destination,int w,int h,const void* source,int width,int height) {
    if(w==width && h==height) {
        std::memcpy(destination,source,static_cast<std::size_t>(w)*h*4);
        return;
    }
    std::memset(destination,0,static_cast<std::size_t>(w)*h*4);
    for(int y=0;y<std::min(h,height);++y)
        std::memcpy(static_cast<unsigned char*>(destination)+static_cast<std::size_t>(y)*w*4,
            static_cast<const unsigned char*>(source)+static_cast<std::size_t>(y)*width*4,static_cast<std::size_t>(std::min(w,width))*4);
}
