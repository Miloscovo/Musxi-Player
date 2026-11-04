#include "../src/cef/overlay_pixels.hpp"
#include <cstdint>
#include <stdexcept>
#include <vector>

int main() {
    for(const auto w:{1,3,5})for(const auto h:{1,3,5})
    for(const auto width:{1,3,5})for(const auto height:{1,3,5}) {
        std::vector<std::uint32_t> source(width*height),destination(w*h,0xffffffffu);
        for(std::size_t i=0;i<source.size();++i)source[i]=static_cast<std::uint32_t>(i)*0x01020304u;
        copyOverlayPixels(destination.data(),w,h,source.data(),width,height);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x)
            if(destination[y*w+x]!=(x<width && y<height?source[y*width+x]:0u))
                throw std::runtime_error("Overlay BGRA copy or transparent padding changed");
    }
}
