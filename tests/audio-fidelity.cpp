#include "../src/audio/ffmpeg_decoder.hpp"
#include "../src/audio/pcm_output.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <random>
#include <chrono>
#include <limits>

namespace {
constexpr double Pi=3.14159265358979323846;
void require(bool value,const char* message) {if(!value)throw std::runtime_error(message);}
void word(std::ostream& out,std::uint32_t value,int bytes) {
    for(int i=0;i<bytes;++i)out.put(static_cast<char>(value>>(i*8)));
}
void wav(const std::filesystem::path& path,int rate,int channels,int bits,bool floating,
         const std::vector<double>& samples,std::uint32_t mask=0) {
    std::ofstream out(path,std::ios::binary);
    const auto size=static_cast<std::uint32_t>(samples.size()*(bits/8));
    const bool extensible=mask!=0;
    out.write("RIFF",4);word(out,size+(extensible?60:36),4);out.write("WAVEfmt ",8);
    word(out,extensible?40:16,4);word(out,extensible?0xfffe:floating?3:1,2);
    word(out,channels,2);word(out,rate,4);word(out,rate*channels*(bits/8),4);
    word(out,channels*(bits/8),2);word(out,bits,2);
    if(extensible) {
        word(out,22,2);word(out,bits,2);word(out,mask,4);word(out,floating?3:1,4);
        word(out,0,2);word(out,0x10,2);
        const unsigned char guid[]={0x80,0,0,0xaa,0,0x38,0x9b,0x71};
        out.write(reinterpret_cast<const char*>(guid),8);
    }
    out.write("data",4);word(out,size,4);
    for(double sample:samples) {
        if(floating) {
            const float value=static_cast<float>(sample);std::uint32_t raw;
            std::memcpy(&raw,&value,4);word(out,raw,4);
        } else word(out,static_cast<std::uint32_t>(static_cast<std::int64_t>(sample*std::ldexp(1.,bits-1))),bits/8);
    }
    require(bool(out),"write fixture");
}
std::vector<float> decode(const std::filesystem::path& file,musxi::PcmFormat format,int* exactBits=nullptr) {
    musxi::FfmpegDecoder decoder;decoder.open(file.wstring(),format);
    std::vector<float> result;std::int64_t cursor=0;
    for(;;) {
        const auto block=decoder.read(997);
        if(block.endOfStream)break;
        if(exactBits)*exactBits=decoder.info().exactIntegerBits;
        require(block.startFrame==cursor,"PCM continuity");
        cursor+=block.samples.size()/format.channels;
        result.insert(result.end(),block.samples.begin(),block.samples.end());
    }
    return result;
}
void exact(const std::filesystem::path& folder) {
    std::mt19937 rng(31);
    for(int bits:{16,24})for(int rate:{44100,96000,192000}) {
        const std::int32_t scale=1<<(bits-1);
        std::vector<double> source={-1.,double(scale-1)/scale,0.,1./scale,-1./scale,0.5,-0.5,0.};
        for(int i=0;i<8192;++i)source.push_back(double(static_cast<std::int32_t>(rng()%(scale*2u))-scale)/scale);
        const auto file=folder/"exact.wav";wav(file,rate,2,bits,false,source);
        int precision=0;const auto result=decode(file,{rate,2,3},&precision);
        require(precision==bits,"integer precision metadata");
        require(result.size()==source.size(),"exact frame count");
        for(std::size_t i=0;i<source.size();++i)require(result[i]==source[i],"16/24-bit bypass changed sample");
        for(const auto target:{std::pair<unsigned,unsigned>{bits,bits},{32u,bits},{24u,24u},{32u,32u}}) {
            const auto container=target.first,valid=target.second;
            std::vector<std::uint8_t> output(result.size()*(container/8));musxi::PcmWriter writer;
            writer.write(output.data(),result.data(),result.size(),false,container,valid,!precision || static_cast<unsigned>(precision)>valid);
            for(std::size_t i=0;i<result.size();++i) {
                std::uint32_t raw=0;
                for(unsigned byte=0;byte<container/8;++byte)raw|=std::uint32_t(output[i*(container/8)+byte])<<(byte*8);
                const auto expected=static_cast<std::uint32_t>(static_cast<std::int32_t>(source[i]*std::ldexp(1.,valid-1)))<<(container-valid);
                if(container<32)raw|=expected&~((1u<<container)-1);
                require(raw==expected,"lossless PCM gained noise or packing error");
            }
        }
    }
    std::vector<double> source={0.,-0.,0.1,-0.1,1.,-1.,1.25,-1.25};
    for(int i=0;i<8192;++i)source.push_back(static_cast<float>(double(rng())/4294967296.-0.5));
    wav(folder/"float.wav",48000,2,32,true,source);
    const auto result=decode(folder/"float.wav",{48000,2,3});
    require(result.size()==source.size(),"float frame count");
    for(std::size_t i=0;i<source.size();++i) {
        const float expected=static_cast<float>(source[i]);
        require(std::memcmp(&result[i],&expected,4)==0,"float bypass changed bits");
    }
    std::vector<double> wide={-1.,double(2147483647)/2147483648.,1./2147483648.,-1./2147483648.};
    for(int i=0;i<8192;++i)wide.push_back(double(static_cast<std::int32_t>(rng()))/2147483648.);
    wav(folder/"wide.wav",48000,2,32,false,wide);
    int wideBits=99;const auto widePcm=decode(folder/"wide.wav",{48000,2,3},&wideBits);
    require(wideBits==0,"32-bit source incorrectly claimed exact");
    require(widePcm.size()==wide.size(),"32-bit frame count");
    for(std::size_t i=0;i<wide.size();++i)require(widePcm[i]==static_cast<float>(wide[i]),"unexpected 32-bit conversion error");
    for(int channels:{6,8}) {
        const std::uint32_t mask=channels==6?0x3f:0x63f;
        std::vector<double> impulse(256*channels);
        for(int channel=0;channel<channels;++channel)impulse[(channel*17+10)*channels+channel]=0.5;
        wav(folder/"channels.wav",48000,channels,24,false,impulse,mask);
        const auto pcm=decode(folder/"channels.wav",{48000,channels,mask});
        require(pcm.size()==impulse.size(),"channel frame count");
        for(std::size_t i=0;i<pcm.size();++i)require(pcm[i]==impulse[i],"channel impulse moved");
        if(channels==6) {
            int precision=99;const auto side=decode(folder/"channels.wav",{48000,6,0x60f},&precision);
            require(precision==0,"different masks treated as unchanged layout");
            require(side==pcm,"back to side channel remap");
        }
    }
    std::cout<<"PASS exact 16/24-bit, float bypass, 5.1/7.1 channel impulses\n";
}
std::int64_t unpack(const std::uint8_t* data,unsigned bits,unsigned validBits) {
    std::uint32_t raw=0;for(unsigned i=0;i<bits/8;++i)raw|=std::uint32_t(data[i])<<(i*8);
    if(bits<32 && (raw&(1u<<(bits-1))))raw|=~((1u<<bits)-1);
    std::int32_t signedValue;std::memcpy(&signedValue,&raw,4);
    return signedValue/(std::int64_t(1)<<(bits-validBits));
}
void quantization() {
    for(const auto format:{std::pair<unsigned,unsigned>{16,16},{24,24},{32,24},{32,32},{16,12}}) {
        const auto bits=format.first,valid=format.second;
        const auto scale=std::int64_t(1)<<(valid-1);
        const std::vector<float> source={-1.f,-0.5f,0.f,0.5f,1.f,-1.25f,1.25f};
        const std::int64_t expected[]={-scale,-scale/2,0,scale/2,scale-1,-scale,scale-1};
        std::vector<std::uint8_t> output(source.size()*(bits/8));musxi::PcmWriter writer;
        writer.write(output.data(),source.data(),source.size(),false,bits,valid,false);
        for(std::size_t i=0;i<source.size();++i) {
            require(unpack(output.data()+i*(bits/8),bits,valid)==expected[i],"integer packing boundary");
            if(bits>valid)require((output[i*(bits/8)]&((1u<<(bits-valid))-1))==0,"valid bits not left aligned");
        }
        require(writer.stats.peak==1.25 && writer.stats.saturated==3,"peak/saturation counters");
    }
    const std::vector<float> samples={0.f,-0.f,0.1234567f,-0.1234567f,1.5f,-1.5f,
        std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity()};
    std::vector<float> result(samples.size());musxi::PcmWriter floating;
    floating.write(reinterpret_cast<std::uint8_t*>(result.data()),samples.data(),samples.size(),true,32,32,true);
    require(!std::memcmp(samples.data(),result.data(),6*4),"float output altered finite samples");
    require(result[6]==0 && result[7]==0 && floating.stats.nonFinite==2 && floating.stats.saturated==0,"nonfinite handling");
    constexpr std::size_t count=262144;
    for(unsigned bits:{16u,24u}) {
        const double scale=std::ldexp(1.,bits-1);const auto bytes=bits/8;
        std::vector<float> low(count);for(std::size_t i=0;i<count;++i)low[i]=float(0.4*std::sin(2*Pi*i/97)/scale);
        std::vector<std::uint8_t> whole(count*bytes),split(count*bytes);musxi::PcmWriter a,b;
        a.write(whole.data(),low.data(),count,false,bits,bits,true);
        b.write(split.data(),low.data(),997,false,bits,bits,true);
        b.write(split.data()+997*bytes,low.data()+997,count-997,false,bits,bits,true);
        require(whole==split,"dither reset at block boundary");
        double mean=0,variance=0,correlation=0,signalEnergy=0;
        for(std::size_t i=0;i<count;++i) {
            const double signal=double(low[i])*scale;
            const double error=unpack(whole.data()+i*bytes,bits,bits)-signal;
            mean+=error;variance+=error*error;correlation+=signal*error;signalEnergy+=signal*signal;
        }
        const double normalized=correlation/std::sqrt(variance*signalEnergy);
        require(std::abs(mean/count)<0.01,"dither DC bias");
        require(variance/count>0.23 && variance/count<0.27,"TPDF error variance");
        require(std::abs(normalized)<0.02,"dither error correlated with signal");
        std::cout<<"PASS "<<bits<<"-bit continuous TPDF: variance="<<variance/count<<" LSB^2 correlation="<<normalized<<'\n';
    }
}
void src(const std::filesystem::path& folder) {
    for(const auto rates:{std::pair<int,int>{44100,48000},{48000,44100},{96000,44100},{96000,48000}}) {
        for(double frequency:{1000.,18000.,20000.,23000.,30000.}) {
            if(frequency>=rates.first/2.)continue;
            std::vector<double> source(rates.first*2);
            for(std::size_t i=0;i<source.size();++i)source[i]=static_cast<float>(0.5*std::sin(2*Pi*frequency*i/rates.first));
            wav(folder/"tone.wav",rates.first,1,32,true,source);
            int precision=99;const auto pcm=decode(folder/"tone.wav",{rates.second,1,4},&precision);
            require(precision==0,"SRC must require final quantization");
            require(std::abs(static_cast<long long>(pcm.size())-rates.second*2)<=1,"SRC tail/frame count");
            double squared=0,peak=0;
            const bool stop=frequency>rates.second/2.;
            for(std::size_t i=512;i+512<pcm.size();++i) {
                require(std::isfinite(pcm[i]),"nonfinite SRC");
                const double ideal=stop?0.:0.5*std::sin(2*Pi*frequency*i/rates.second);
                const double error=pcm[i]-ideal;squared+=error*error;
                peak=std::max(peak,std::abs(double(pcm[i])));
            }
            const double rms=std::sqrt(squared/(pcm.size()-1024));
            std::cout<<"SRC "<<rates.first<<" -> "<<rates.second<<" tone="<<frequency
                <<" "<<(stop?"alias":"error")<<"_dBFS="<<20*std::log10(std::max(rms,1e-20))<<" peak="<<peak<<'\n';
            if(frequency==1000)require(rms<0.0001,"SRC unexpected low-frequency gain/phase error");
            if(frequency<=20000)require(rms<0.00003,"SRC passband error above -90 dBFS");
            if(stop)require(rms<0.000018,"SRC alias above -95 dBFS");
        }
    }
    std::vector<double> source(48000*6,0.75);
    wav(folder/"downmix.wav",48000,6,32,true,source,0x3f);
    const auto pcm=decode(folder/"downmix.wav",{48000,2,3});
    double peak=0;for(float sample:pcm)peak=std::max(peak,std::abs(double(sample)));
    require(peak<=0.750001 && peak>0.749,"downmix matrix normalization");
    std::cout<<"DOWNMIX correlated 5.1 -> stereo peak="<<peak<<" (normalized matrix)\n";
    for(int channels:{2,8}) {
        std::vector<double> stress(192000*channels*2);
        for(std::size_t i=0;i<stress.size();++i)stress[i]=0.25*std::sin(2*Pi*(i/channels)*1000/192000.);
        wav(folder/"stress.wav",192000,channels,32,true,stress,channels==8?0x63f:3);
        const auto start=std::chrono::steady_clock::now();
        const auto output=decode(folder/"stress.wav",{48000,channels,channels==8?0x63fu:3u});
        const double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
        require(output.size()==48000u*channels*2,"stress output frame count");
        std::cout<<"SRC 192k -> 48k "<<channels<<" channels: "<<seconds<<" s for 2 s audio ("<<2/seconds<<"x realtime)\n";
    }
}
}
int main(int argc,char** argv) {
    try {
        require(argc==2,"fixture directory required");
        const std::filesystem::path folder=argv[1];std::filesystem::create_directories(folder);
        exact(folder);quantization();src(folder);
        return 0;
    } catch(const std::exception& e) {std::cerr<<"FAIL "<<e.what()<<'\n';return 1;}
}
