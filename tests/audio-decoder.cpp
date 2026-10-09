#include "../src/audio/ffmpeg_decoder.hpp"
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <numeric>
#include <chrono>
#include <thread>

namespace fs=std::filesystem;
using musxi::FfmpegDecoder;
void require(bool ok,const std::string& why) {if(!ok)throw std::runtime_error(why);}
template<class F> void fails(F action,const char* why) {
    try {action();} catch(const std::exception&) {return;}
    throw std::runtime_error(why);
}
std::vector<float> reference(const fs::path& file) {
    const auto bytes=fs::file_size(file);require(bytes%sizeof(float)==0,"reference size");
    std::vector<float> data(bytes/sizeof(float));std::ifstream in(file,std::ios::binary);
    in.read(reinterpret_cast<char*>(data.data()),static_cast<std::streamsize>(bytes));require(bool(in),"reference read");return data;
}
std::vector<float> decode(FfmpegDecoder& decoder) {
    std::vector<float> result;std::int64_t end=-1;
    for(int i=0;i<10000;++i) {
        auto block=decoder.read(997);
        if(block.endOfStream)return result;
        require(!block.samples.empty() && block.samples.size()<=997u*decoder.info().output.channels,"bounded read");
        if(end>=0)require(block.startFrame==end,"PCM continuity");
        else require(block.startFrame==0,"initial PCM origin");
        end=block.startFrame+static_cast<std::int64_t>(block.samples.size()/decoder.info().output.channels);
        for(auto sample:block.samples)require(std::isfinite(sample),"finite sample");
        result.insert(result.end(),block.samples.begin(),block.samples.end());
    }
    throw std::runtime_error("decoder did not reach EOF");
}
void compare(const std::vector<float>& actual,const std::vector<float>& expected,const std::string& label) {
    require(actual.size()==expected.size(),label+" tail/frame count: "+std::to_string(actual.size())+" != "+std::to_string(expected.size()));
    double maxError=0;
    for(std::size_t i=0;i<actual.size();++i)maxError=std::max(maxError,std::abs(double(actual[i])-expected[i]));
    require(maxError<0.0001,label+" PCM differs: "+std::to_string(maxError));
}
int main(int argc,char** argv) {
    try {
        require(argc==2,"fixture directory required");const fs::path folder=argv[1];
        FfmpegDecoder decoder;
        fails([&]{decoder.read();},"read without open succeeded");
        for(const auto ext:{"wav","mp3","flac","m4a","aac","ogg","opus","wma"}) {
            decoder.open((folder/(std::string("source.")+ext)).wstring());
            require(decoder.info().sourceChannels==2 && decoder.info().sourceSampleRate>0,"source info");
            require(decoder.info().durationMs>3000 && decoder.info().durationMs<3400,"duration");
            auto all=decode(decoder);compare(all,reference(folder/(std::string(ext)+".f32")),ext);
            require(decoder.read().endOfStream,"repeated EOF");
            for(auto ms:{1700,250,2900,0}) {
                decoder.seek(ms);auto block=decoder.read(2048);
                require(!block.endOfStream && block.startFrame==ms*48,"seek landing: "+std::string(ext));
                const auto start=static_cast<std::size_t>(block.startFrame)*2;
                require(start+block.samples.size()<=all.size(),"seek range");
                // Lossy codecs can need preroll; one second excludes reservoir/filter startup.
                double squared=0;
                for(std::size_t i=0;i<block.samples.size();++i) {
                    auto d=block.samples[i]-all[start+i];squared+=d*d;
                }
                require(std::sqrt(squared/block.samples.size())<0.015,"seek PCM alignment: "+std::string(ext)+" ms="+std::to_string(ms)+" rms="+std::to_string(std::sqrt(squared/block.samples.size())));
            }
            std::cout<<"PASS "<<ext<<" decode, tail and repeated seek\n";
        }
        decoder.open((folder/"source.wav").wstring(),{32000,1,0});compare(decode(decoder),reference(folder/"mono.f32"),"mono");
        decoder.open((folder/"source.wav").wstring(),{96000,6,0x3f});compare(decode(decoder),reference(folder/"surround.f32"),"surround");
        const auto unicode=folder/L"中文 空格 · 错误扩展名.mp3";
        fs::copy_file(folder/"source.flac",unicode,fs::copy_options::overwrite_existing);
        decoder.open(unicode.wstring());compare(decode(decoder),reference(folder/"flac.f32"),"Unicode/probing");
        decoder.seek(999999);require(decoder.read().endOfStream,"seek beyond duration clamps to end");
        require(musxi::FfmpegDecoder::embeddedLyrics((folder/"source.wav").wstring()).empty(),"untagged file reported lyrics");
        for(const auto* ext:{"flac","mp3","m4a"})
            require(musxi::FfmpegDecoder::embeddedLyrics((folder/(std::string("lyrics.")+ext)).wstring())=="[00:00.50]Tagged line",std::string("embedded lyrics: ")+ext);
        decoder.close();decoder.close();
        // Renaming checks the decoder has released its local file handle.
        const auto renamed=folder/L"released.mp3";if(fs::exists(renamed))fs::remove(renamed);
        fs::rename(unicode,renamed);fs::rename(renamed,unicode);
        fails([&]{decoder.open(L"https://example.com/audio.mp3");},"network URL accepted");
        fails([&]{decoder.open((folder/"absent.wav").wstring());},"missing file accepted");
        fails([&]{decoder.open(unicode.wstring(),{0,2,0});},"invalid sample rate accepted");
        fails([&]{decoder.open(unicode.wstring(),{48000,2,4});},"mismatched channel mask accepted");
        {std::ofstream corrupt(folder/"broken.mp3",std::ios::binary);corrupt<<"not audio";}
        fails([&]{decoder.open((folder/"broken.mp3").wstring());},"corrupt file accepted");
        decoder.open(unicode.wstring());
        fails([&]{decoder.read(0);},"zero read accepted");
        fails([&]{decoder.seek(-1);},"negative seek accepted");
        require(!decoder.read().samples.empty(),"invalid command destroyed valid stream");
        std::atomic_bool cancelled{true};FfmpegDecoder cancellable(&cancelled);
        fails([&]{cancellable.open(unicode.wstring());},"cancelled open succeeded");
        cancelled=false;cancellable.open((folder/"long.wma").wstring());cancellable.seek(170000);
        const auto start=std::chrono::steady_clock::now();
        std::thread cancel([&]{std::this_thread::sleep_for(std::chrono::milliseconds(2));cancelled=true;});
        bool interrupted=false;
        try{cancellable.read();}catch(const musxi::DecodeError&){interrupted=true;}
        cancel.join();
        require(interrupted,"long WMA seek did not observe cancellation");
        require(std::chrono::steady_clock::now()-start<std::chrono::seconds(1),"decode cancellation too slow");
        cancelled=false;cancellable.open(unicode.wstring());require(!cancellable.read().samples.empty(),"reopen after cancellation");
        std::cout<<"PASS PCM formats, Unicode paths, limits, errors and file lifecycle\n";
        return 0;
    } catch(const std::exception& e) {std::cerr<<"FAIL "<<e.what()<<'\n';return 1;}
}
