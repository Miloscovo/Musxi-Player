#include "ffmpeg_decoder.hpp"
#include "../cxx17_guard.hpp"
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <limits>

namespace musxi {
namespace {
[[noreturn]] void fail(const char* operation,int code) {
    char message[AV_ERROR_MAX_STRING_SIZE]{};av_strerror(code,message,sizeof(message));
    throw DecodeError(std::string(operation)+": "+message,code);
}
void check(int result,const char* operation) {if(result<0)fail(operation,result);}
constexpr int MaxConvertedFrames=262144;
}
struct FfmpegDecoder::Impl {
    FILE* file=nullptr;
    AVIOContext* io=nullptr;
    AVFormatContext* format=nullptr;
    AVCodecContext* codec=nullptr;
    AVPacket* packet=nullptr;
    AVFrame* frame=nullptr;
    SwrContext* resampler=nullptr;
    AVChannelLayout inputLayout{},outputLayout{};
    DecodedAudioInfo info;
    std::wstring path;
    int stream=-1,inputRate=0,inputFormat=-1;
    bool drainSent=false,decodedEof=false,finished=false,timeline=false,afterSeek=false;
    std::int64_t cursor=0,target=0,pendingStart=0;
    std::vector<float> pending;
    std::size_t offset=0;
    ~Impl() {
        swr_free(&resampler);av_channel_layout_uninit(&inputLayout);av_channel_layout_uninit(&outputLayout);
        av_frame_free(&frame);av_packet_free(&packet);avcodec_free_context(&codec);
        avformat_close_input(&format);
        if(io){av_freep(&io->buffer);avio_context_free(&io);}
        if(file)fclose(file);
    }
    static int readFile(void* opaque,std::uint8_t* buffer,int size) {
        auto f=static_cast<Impl*>(opaque)->file;
        const auto got=fread(buffer,1,static_cast<std::size_t>(size),f);
        if(got)return static_cast<int>(got);
        return ferror(f)?AVERROR(EIO):AVERROR_EOF;
    }
    static std::int64_t seekFile(void* opaque,std::int64_t offset,int whence) {
        auto f=static_cast<Impl*>(opaque)->file;
        if(whence & AVSEEK_SIZE) {
            const auto current=_ftelli64(f);
            if(current<0 || _fseeki64(f,0,SEEK_END))return AVERROR(EIO);
            const auto size=_ftelli64(f);
            if(_fseeki64(f,current,SEEK_SET))return AVERROR(EIO);
            return size;
        }
        whence &= ~AVSEEK_FORCE;
        if(whence!=SEEK_SET && whence!=SEEK_CUR && whence!=SEEK_END)return AVERROR(EINVAL);
        if(_fseeki64(f,offset,whence))return AVERROR(EIO);
        return _ftelli64(f);
    }
    void initialize(const std::wstring& localPath,PcmFormat output) {
        path=localPath;
        if((avcodec_version()>>16)!=LIBAVCODEC_VERSION_MAJOR ||
           (avformat_version()>>16)!=LIBAVFORMAT_VERSION_MAJOR ||
           (avutil_version()>>16)!=LIBAVUTIL_VERSION_MAJOR ||
           (swresample_version()>>16)!=LIBSWRESAMPLE_VERSION_MAJOR)
            fail("FFmpeg runtime/header major version mismatch",AVERROR(EINVAL));
        if(output.sampleRate<8000 || output.sampleRate>192000 || output.channels<1 || output.channels>8)
            fail("Invalid PCM format",AVERROR(EINVAL));
        if(path.empty() || path.find(L'\0')!=std::wstring::npos || !std::filesystem::is_regular_file(path))
            fail("Expected a local audio file",AVERROR(ENOENT));
        if(output.channelMask)check(av_channel_layout_from_mask(&outputLayout,output.channelMask),"Channel mask");
        else av_channel_layout_default(&outputLayout,output.channels);
        if(outputLayout.nb_channels!=output.channels || !av_channel_layout_check(&outputLayout))
            fail("Channel count/mask mismatch",AVERROR(EINVAL));
        info.output=output;
        if(outputLayout.order==AV_CHANNEL_ORDER_NATIVE)info.output.channelMask=outputLayout.u.mask;
        if(_wfopen_s(&file,path.c_str(),L"rb"))fail("Open file",AVERROR(errno));
        auto buffer=static_cast<unsigned char*>(av_malloc(32768));
        if(!buffer)fail("Allocate input",AVERROR(ENOMEM));
        io=avio_alloc_context(buffer,32768,0,this,readFile,nullptr,seekFile);
        if(!io){av_free(buffer);fail("Allocate input context",AVERROR(ENOMEM));}
        format=avformat_alloc_context();
        if(!format)fail("Allocate demuxer",AVERROR(ENOMEM));
        format->pb=io;format->flags|=AVFMT_FLAG_CUSTOM_IO;
        // A2 accepts only local audio containers, not playlists or external URLs.
        check(av_opt_set(format,"format_whitelist","wav,mp3,flac,mov,aac,ogg,asf",0),"Demuxer policy");
        check(av_opt_set(format,"protocol_whitelist","file",0),"Protocol policy");
        check(avformat_open_input(&format,nullptr,nullptr,nullptr),"Probe audio");
        check(avformat_find_stream_info(format,nullptr),"Read stream info");
        stream=av_find_best_stream(format,AVMEDIA_TYPE_AUDIO,-1,-1,nullptr,0);check(stream,"Find audio stream");
        const auto st=format->streams[stream];
        const auto decoder=avcodec_find_decoder(st->codecpar->codec_id);
        if(!decoder)fail("Unsupported decoder",AVERROR_DECODER_NOT_FOUND);
        codec=avcodec_alloc_context3(decoder);if(!codec)fail("Allocate decoder",AVERROR(ENOMEM));
        check(avcodec_parameters_to_context(codec,st->codecpar),"Decoder parameters");
        codec->pkt_timebase=st->time_base;codec->thread_count=1;
        check(avcodec_open2(codec,decoder,nullptr),"Open decoder");
        info.sourceSampleRate=codec->sample_rate;info.sourceChannels=codec->ch_layout.nb_channels;
        if(st->duration!=AV_NOPTS_VALUE)info.durationMs=av_rescale_q(st->duration,st->time_base,{1,1000});
        else if(format->duration!=AV_NOPTS_VALUE)info.durationMs=av_rescale_q(format->duration,AV_TIME_BASE_Q,{1,1000});
        packet=av_packet_alloc();frame=av_frame_alloc();
        if(!packet || !frame)fail("Allocate decoder buffers",AVERROR(ENOMEM));
    }
    void convert(const AVFrame* decoded) {
        if(decoded) {
            if(decoded->sample_rate<=0 || decoded->nb_samples<0 || decoded->nb_samples>262144 ||
               decoded->ch_layout.nb_channels<1 || decoded->ch_layout.nb_channels>8)
                fail("Unsupported audio frame",AVERROR_INVALIDDATA);
            if(!resampler) {
                inputRate=decoded->sample_rate;inputFormat=decoded->format;
                check(av_channel_layout_copy(&inputLayout,&decoded->ch_layout),"Input layout");
                check(swr_alloc_set_opts2(&resampler,&outputLayout,AV_SAMPLE_FMT_FLT,info.output.sampleRate,
                    &inputLayout,static_cast<AVSampleFormat>(inputFormat),inputRate,0,nullptr),"Create resampler");
                check(swr_init(resampler),"Initialize resampler");
            } else if(inputRate!=decoded->sample_rate || inputFormat!=decoded->format ||
                      av_channel_layout_compare(&inputLayout,&decoded->ch_layout))
                fail("Midstream format change is not supported",AVERROR_INPUT_CHANGED);
            if(!timeline) {
                const auto st=format->streams[stream];const auto pts=decoded->best_effort_timestamp;
                if(pts==AV_NOPTS_VALUE && afterSeek)fail("Seek requires timestamps",AVERROR_INVALIDDATA);
                const auto origin=st->start_time==AV_NOPTS_VALUE?0:st->start_time;
                cursor=!afterSeek || pts==AV_NOPTS_VALUE?0:av_rescale_q(pts-origin,st->time_base,{1,info.output.sampleRate});
                timeline=true;
            }
        }
        if(!resampler){finished=true;return;}
        const int capacity=swr_get_out_samples(resampler,decoded?decoded->nb_samples:0);
        check(capacity,"PCM capacity");
        if(capacity>MaxConvertedFrames)fail("PCM frame limit exceeded",AVERROR(ENOBUFS));
        pending.resize(static_cast<std::size_t>(capacity)*info.output.channels);
        std::uint8_t* out=reinterpret_cast<std::uint8_t*>(pending.data());
        const int frames=swr_convert(resampler,&out,capacity,
            decoded?const_cast<const std::uint8_t**>(decoded->extended_data):nullptr,decoded?decoded->nb_samples:0);
        check(frames,"Resample");
        pending.resize(static_cast<std::size_t>(frames)*info.output.channels);
        pendingStart=cursor;cursor+=frames;offset=0;
        if(target>pendingStart)offset=static_cast<std::size_t>(std::min<std::int64_t>(frames,target-pendingStart))*info.output.channels;
        if(!decoded && !frames)finished=true;
    }
    void produce() {
        pending.clear();offset=0;
        if(decodedEof){convert(nullptr);return;}
        for(;;) {
            const auto received=avcodec_receive_frame(codec,frame);
            if(received==0){convert(frame);av_frame_unref(frame);return;}
            if(received==AVERROR_EOF){decodedEof=true;convert(nullptr);return;}
            if(received!=AVERROR(EAGAIN))fail("Decode frame",received);
            if(drainSent)fail("Decoder stalled while draining",AVERROR_INVALIDDATA);
            int status=0;
            do {
                av_packet_unref(packet);status=av_read_frame(format,packet);
            } while(status>=0 && packet->stream_index!=stream);
            if(status==AVERROR_EOF) {
                check(avcodec_send_packet(codec,nullptr),"Drain decoder");drainSent=true;
            } else {
                check(status,"Read packet");
                check(avcodec_send_packet(codec,packet),"Send packet");av_packet_unref(packet);
            }
        }
    }
};
FfmpegDecoder::FfmpegDecoder()=default;
FfmpegDecoder::~FfmpegDecoder()=default;
void FfmpegDecoder::close() noexcept {impl_.reset();}
void FfmpegDecoder::open(const std::wstring& path,PcmFormat output) {
    close();auto next=std::make_unique<Impl>();next->initialize(path,output);impl_=std::move(next);
}
const DecodedAudioInfo& FfmpegDecoder::info() const {
    if(!impl_)fail("No audio file",AVERROR(EINVAL));return impl_->info;
}
PcmBlock FfmpegDecoder::read(std::uint32_t maxFrames) {
    if(!impl_ || !maxFrames || maxFrames>65536)fail("Invalid read",AVERROR(EINVAL));
    auto& s=*impl_;
    try {
        while(s.offset==s.pending.size() && !s.finished)s.produce();
        PcmBlock block;block.startFrame=s.pendingStart+static_cast<std::int64_t>(s.offset/s.info.output.channels);
        if(s.offset==s.pending.size()){block.endOfStream=true;return block;}
        const auto size=std::min(s.pending.size()-s.offset,static_cast<std::size_t>(maxFrames)*s.info.output.channels);
        block.samples.assign(s.pending.begin()+s.offset,s.pending.begin()+s.offset+size);s.offset+=size;
        return block;
    } catch(...) {close();throw;}
}
void FfmpegDecoder::seek(std::int64_t positionMs) {
    if(!impl_ || positionMs<0)fail("Invalid seek",AVERROR(EINVAL));
    auto& s=*impl_;
    if(s.info.durationMs>=0)positionMs=std::min(positionMs,s.info.durationMs);
    if(s.info.durationMs>=0 && positionMs==s.info.durationMs) {
        s.pending.clear();s.offset=0;s.finished=true;
        s.pendingStart=av_rescale_q(positionMs,{1,1000},{1,s.info.output.sampleRate});
        return;
    }
    // Reopening preserves container priming/skip metadata at the beginning
    // (notably AAC edit lists), which a demuxer seek to timestamp zero loses.
    // ponytail: ASF packet timestamps are too coarse for sample-accurate WMA
    // positioning here. Decode from the start; add an index if long-file seek
    // latency becomes a problem (this synchronous decoder runs off the UI).
    if(positionMs<=1000 || std::strcmp(s.format->iformat->name,"asf")==0) {
        const auto path=s.path;const auto output=s.info.output;
        open(path,output);
        impl_->target=av_rescale_q(positionMs,{1,1000},{1,output.sampleRate});
        return;
    }
    const auto st=s.format->streams[s.stream];
    const auto origin=st->start_time==AV_NOPTS_VALUE?0:st->start_time;
    // Seek backwards with decoder preroll, then discard PCM before the target.
    const auto timestamp=origin+av_rescale_q(std::max<std::int64_t>(0,positionMs-1000),{1,1000},st->time_base);
    try {
        check(avformat_seek_file(s.format,s.stream,INT64_MIN,timestamp,timestamp,0),"Seek file");
        avcodec_flush_buffers(s.codec);av_packet_unref(s.packet);av_frame_unref(s.frame);
        swr_free(&s.resampler);av_channel_layout_uninit(&s.inputLayout);
        s.pending.clear();s.offset=0;s.drainSent=s.decodedEof=s.finished=s.timeline=false;s.afterSeek=true;
        s.target=av_rescale_q(positionMs,{1,1000},{1,s.info.output.sampleRate});
        s.cursor=s.pendingStart=s.target;
    } catch(...) {close();throw;}
}
}
