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
    // Frozen layouts keep their original pixels; only newly exposed space uses the sampled background.
    std::vector<std::uint32_t> source(9),canvas(20);
    for(std::size_t i=0;i<source.size();++i)source[i]=0x80000000u+static_cast<std::uint32_t>(i);
    copyResizingWindowPixels(canvas.data(),5,4,source.data(),3,3,1,0);
    for(int y=0;y<4;++y)for(int x=0;x<5;++x)
        if(canvas[y*5+x]!=(x<3 && y<3?source[y*3+x]:source[4]))throw std::runtime_error("Frozen viewport was scaled or background alpha changed");
    copyResizingWindowPixels(canvas.data(),2,1,source.data(),3,3,1,0);
    if(canvas[0]!=source[0] || canvas[1]!=source[1])throw std::runtime_error("Frozen viewport did not clip at original scale");
    source.assign(100,0x80302010u);canvas.resize(144);
    copyResizingWindowPixels(canvas.data(),12,12,source.data(),10,10,2,3);
    if((canvas[0]>>24)>=128 || canvas[6*12+6]!=0x80302010u)throw std::runtime_error("Resize corner rounding changed interior pixels");
    source.assign(100,0);
    copyResizingWindowPixels(canvas.data(),12,12,source.data(),10,10,2,3);
    if(std::any_of(canvas.begin(),canvas.end(),[](auto pixel){return pixel!=0;}))throw std::runtime_error("Resize padding made a transparent window opaque");
}
