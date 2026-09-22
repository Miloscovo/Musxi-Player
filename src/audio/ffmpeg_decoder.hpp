#pragma once
#include <cstdint>
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace musxi {
struct PcmFormat {
    int sampleRate=48000;
    int channels=2;
    // Native speaker-position bit mask; zero chooses the default channel layout.
    std::uint64_t channelMask=0;
};
struct DecodedAudioInfo {
    std::int64_t durationMs=-1; // Unknown is distinct from an empty stream.
    int sourceSampleRate=0,sourceChannels=0;
    PcmFormat output;
};
struct PcmBlock {
    std::vector<float> samples; // Interleaved, frames * output.channels.
    std::int64_t startFrame=0; // Media position in output sample-rate units.
    bool endOfStream=false;
};
class DecodeError : public std::runtime_error {
public:
    DecodeError(const std::string& message,int code):std::runtime_error(message),nativeCode(code) {}
    int nativeCode;
};
// Synchronous, single-owner decoder: call from a decoding worker in A3.
// No audio device, player state, network, CEF, or FFmpeg types in this boundary.
class FfmpegDecoder {
public:
    // Optional flag must outlive this decoder; only the flag may be set by another thread.
    explicit FfmpegDecoder(const std::atomic_bool* cancelled=nullptr);
    ~FfmpegDecoder();
    FfmpegDecoder(const FfmpegDecoder&)=delete;
    FfmpegDecoder& operator=(const FfmpegDecoder&)=delete;
    void open(const std::wstring& localPath,PcmFormat output={});
    void close() noexcept;
    const DecodedAudioInfo& info() const;
    PcmBlock read(std::uint32_t maxFrames=4096);
    void seek(std::int64_t positionMs);
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    const std::atomic_bool* cancelled_;
};
}
