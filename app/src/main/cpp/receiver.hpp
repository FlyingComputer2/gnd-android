#pragma once
#include "core/rtp_h264.hpp"
#include <functional>
#include <vector>

namespace rover {
// P4 has already removed stream framing and recovered radio FEC.
class Receiver {
public:
    using Frame = std::function<void(const std::vector<uint8_t>&,int64_t,bool)>;
    explicit Receiver(Frame output);
    bool ingest(const uint8_t* bytes,size_t len);
    uint64_t bad_packets=0, frames=0;
private:
    vstreamer::rtp_h264_depacketizer depay;
    Frame output;
};
}
