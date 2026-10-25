#include "../src/audio/output_drain.hpp"
#include <iostream>
#include <stdexcept>
using namespace std::chrono_literals;
void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
int main() {
    try {
        musxi::OutputDrain drain;
        const auto start=musxi::OutputDrain::Clock::time_point{};
        require(!drain.complete(false,48000,48000,start,150ms),"ended with audio still queued");
        require(drain.complete(true,48000,48000,start,150ms),"normal clock did not finish immediately");
        drain.reset();
        require(!drain.complete(true,47999,48000,start,150ms),"truncated the device tail");
        require(!drain.complete(true,47999,48000,start+149ms,150ms),"latency grace was ignored");
        require(drain.complete(true,47999,48000,start+150ms,150ms),"stalled final clock blocked EOF indefinitely");
        drain.reset();
        require(!drain.complete(true,0,48000,start,150ms),"stalled clock skipped the initial grace");
        require(!drain.complete(false,0,48000,start+1s,150ms),"refilled audio did not cancel drain");
        require(!drain.complete(true,0,48000,start+2s,150ms),"old drain deadline leaked into refilled audio");
        drain.reset();
        require(!drain.complete(true,0,48000,start+3s,150ms),"reset retained a previous deadline");
        require(drain.complete(true,0,0,start,150ms),"empty decoded stream stalled");
        std::cout<<"PASS final clock stall, latency grace, refill/reset and empty stream\n";
        return 0;
    } catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<'\n';return 1;}
}
