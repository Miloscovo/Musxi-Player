#pragma once
#include <algorithm>
#include <cstddef>
#include <cmath>
#include <cstdint>
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

// Keep the frozen viewport at original scale and round the actual window boundary.
inline void copyResizingWindowPixels(void* destination,int w,int h,const void* source,int width,int height,int backgroundRow,int cornerRadius) {
    auto* destinationPixels=static_cast<std::uint8_t*>(destination);
    const auto* sourcePixels=static_cast<const std::uint8_t*>(source);
    const auto background=sourcePixels+(static_cast<size_t>(std::min(height-1,backgroundRow))*width+width/2)*4;
    const int copyWidth=std::min(width,w),copyHeight=std::min(height,h);
    for(int y=0;y<h;++y) {
        auto row=destinationPixels+static_cast<size_t>(y)*w*4;
        const int copied=y<copyHeight?copyWidth:0;
        if(copied)std::memcpy(row,sourcePixels+static_cast<size_t>(y)*width*4,static_cast<size_t>(copied)*4);
        for(int x=copied;x<w;++x)std::memcpy(row+static_cast<size_t>(x)*4,background,4);
    }
    // Flatten the old CSS corners before rounding the actual window boundary.
    const int radius=std::min({cornerRadius,width/2,height/2,w/2,h/2});
    for(const bool right:{false,true})for(const bool bottom:{false,true})for(int y=0;y<radius;++y) {
        const int sourceY=bottom?height-1-y:y;
        if(sourceY>=h)continue;
        const auto edge=sourcePixels+(static_cast<size_t>(bottom?std::max(0,height-2):std::min(1,height-1))*width+(right?width-radius-1:radius))*4;
        for(int x=0;x<radius;++x) {
            const int sourceX=right?width-1-x:x;
            if(sourceX<w && std::hypot(radius-x-.5,radius-y-.5)>radius-.5)
                std::memcpy(destinationPixels+(static_cast<size_t>(sourceY)*w+sourceX)*4,edge,4);
        }
    }
    for(int y=0;y<radius;++y)for(int x=0;x<radius;++x) {
        const double coverage=std::clamp(radius+.5-std::hypot(radius-x-.5,radius-y-.5),0.0,1.0);
        for(const bool right:{false,true})for(const bool bottom:{false,true}) {
            auto corner=destinationPixels+(static_cast<size_t>(bottom?h-1-y:y)*w+(right?w-1-x:x))*4;
            for(int channel=0;channel<4;++channel)corner[channel]=static_cast<std::uint8_t>(std::lround(corner[channel]*coverage));
        }
    }
}
