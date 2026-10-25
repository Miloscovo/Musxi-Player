#pragma once
#include <chrono>
#include <cstdint>
#include <optional>

namespace musxi {
class OutputDrain {
public:
    using Clock=std::chrono::steady_clock;
    void reset() { since.reset(); }
    bool complete(bool drained,long double played,std::uint64_t submitted,Clock::time_point now,
                  Clock::duration grace) {
        if(!drained){reset();return false;}
        if(played>=submitted)return true;
        if(!since)since=now;
        return now-*since>=grace;
    }
private:
    std::optional<Clock::time_point> since;
};
}
