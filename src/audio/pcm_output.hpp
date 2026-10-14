#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>

namespace musxi {
struct PcmWriteStats {
    double peak=0;
    std::uint64_t nonFinite=0,saturated=0;
};
// Final endpoint conversion only; float output never receives dither or clipping.
class PcmWriter {
    std::uint32_t random_=0x9e3779b9u;
    double uniform() {
        random_^=random_<<13;random_^=random_>>17;random_^=random_<<5;
        return double(random_)/4294967296.;
    }
public:
    PcmWriteStats stats;
    void write(std::uint8_t* output,const float* input,std::size_t count,
               bool floating,unsigned bits,unsigned validBits,bool dither) {
        if(floating?bits!=32:((bits!=16 && bits!=24 && bits!=32) || !validBits || validBits>bits))
            throw std::invalid_argument("Invalid endpoint PCM format");
        const auto scale=std::int64_t(1)<<(floating?0:validBits-1);
        for(std::size_t i=0;i<count;++i) {
            float sample=input[i];
            if(!std::isfinite(sample)){sample=0;++stats.nonFinite;}
            stats.peak=std::max(stats.peak,std::abs(double(sample)));
            if(floating)std::memcpy(output+i*4,&sample,4);
            else {
                // Add TPDF in double so a 24-bit LSB is not rounded away in float.
                const double noise=dither?uniform()-uniform():0.;
                const auto rounded=std::llround(std::clamp(double(sample),-1.,1.)*scale+noise);
                const auto integer=std::clamp<std::int64_t>(rounded,-scale,scale-1);
                if(sample<-1.f || sample>1.f || integer!=rounded)++stats.saturated;
                const auto packed=static_cast<std::uint32_t>(integer)<<(bits-validBits);
                for(unsigned byte=0;byte<bits/8;++byte)output[i*(bits/8)+byte]=static_cast<std::uint8_t>(packed>>(byte*8));
            }
        }
    }
};
}
