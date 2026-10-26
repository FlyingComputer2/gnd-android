#pragma once
#include "core/rs_block_erasure.hpp"
#include "core/rtp_h264.hpp"
#include "core/stream_telemetry.hpp"
#include <functional>
#include <vector>

namespace rover {
class Receiver {
public:
    using Frame = std::function<void(const std::vector<uint8_t>&,int64_t,bool)>;
    explicit Receiver(Frame output, uint32_t session=1);
    bool ingest(const uint8_t* bytes,size_t len);
    void tick();
    std::vector<uint8_t> feedback();
    uint64_t bad_packets=0, frames=0;
private:
    void deliver(vstreamer::fec_rx_payload_list& payloads);
    vstreamer::rs_block_erasure fec;
    vstreamer::rtp_h264_depacketizer depay;
    vstreamer::stream_link_report report;
    Frame output;
    bool have_sequence=false;
    uint16_t last_sequence=0, feedback_sequence=0;
};
}
