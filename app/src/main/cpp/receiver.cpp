#include "receiver.hpp"
#include "core/stream_header.hpp"
#include <chrono>

namespace rover {
Receiver::Receiver(Frame cb,uint32_t session): output(std::move(cb)) { report.session_id=session?session:1; }
bool Receiver::ingest(const uint8_t* bytes,size_t len) {
    vstreamer::stream_header h{}; const uint8_t* payload=nullptr; size_t size=0;
    if(len>1445 || vstreamer::stream_header_parse(bytes,len,&h,&payload,&size)<0 || !h.is_stream_data || !size) {
        ++bad_packets; return false;
    }
    if(have_sequence) {
        uint16_t delta=h.sequence_number-last_sequence;
        if(delta && delta<0x8000) { report.counters.udp_gap_count+=delta-1; last_sequence=h.sequence_number; }
    } else { have_sequence=true; last_sequence=h.sequence_number; }
    ++report.counters.udp_packet_received; report.counters.udp_bytes_received+=len;
    vstreamer::fec_rx_payload_list out;
    if(h.is_fec) fec.push_air(payload,size,&out);
    else out.push_back(bfc::shared_sized_buffer::copy_from(payload,size));
    deliver(out); return true;
}
void Receiver::deliver(vstreamer::fec_rx_payload_list& payloads) {
    report.counters.fec_gap_count+=fec.take_fail_lost_app_pkts();
    for(auto& p:payloads) {
        ++report.counters.fec_packet_received; report.counters.fec_bytes_received+=p.size();
        std::vector<uint8_t> au;
        if(depay.feed(p.u8(),p.size(),&au)>0) {
            ++frames; output(au,depay.au_pts()*1000000/30,depay.au_key());
        }
    }
}
void Receiver::tick() { vstreamer::fec_rx_payload_list out; fec.poll_rx(&out); deliver(out); }
std::vector<uint8_t> Receiver::feedback() {
    if(!have_sequence) return {}; // Match vstreamer: report only after valid media.
    std::vector<uint8_t> out(vstreamer::k_stream_header_len+vstreamer::k_stream_link_report_payload_len);
    report.timestamp_us=std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    report.report_seq=feedback_sequence;
    vstreamer::stream_header h{feedback_sequence++,false,false,0};
    vstreamer::stream_header_write(out.data(),h);
    vstreamer::stream_link_report_encode_payload(report,out.data()+vstreamer::k_stream_header_len,
                                                vstreamer::k_stream_link_report_payload_len);
    return out;
}
}
