#include "receiver.hpp"
#include "core/stream_header.hpp"
#include <cassert>
#include <cstdio>

int main() {
    // Synthetic Annex-B IDR large enough for fragmented RTP and RS recovery.
    std::vector<uint8_t> au{0,0,0,1,0x65};
    au.resize(3005,0x55);
    vstreamer::rtp_h264_packer packer({300,96,0x1234,30});
    assert(packer.pack_annexb(au.data(),au.size(),0)>=0);
    std::vector<std::vector<uint8_t>> rtp;
    uint8_t packet[1600];
    while(packer.pending()) { int n=packer.pop_datagram(packet,sizeof(packet)); assert(n>0); rtp.emplace_back(packet,packet+n); }
    unsigned frames=0;
    rover::Receiver receiver([&](const auto& frame,int64_t,bool key){ assert(frame==au); assert(key); ++frames; });
    assert(receiver.feedback().empty()); // Do not report a healthy empty link before media.
    vstreamer::rs_block_erasure encoder;
    assert(encoder.init(3,5,20,1441));
    std::vector<std::vector<uint8_t>> shards;
    for(auto& p:rtp) {
        std::vector<std::vector<uint8_t>> batch;
        encoder.push_app(p.data(),p.size(),&batch);
        shards.insert(shards.end(),batch.begin(),batch.end());
    }
    std::vector<std::vector<uint8_t>> tail;
    encoder.flush(&tail); shards.insert(shards.end(),tail.begin(),tail.end());
    uint16_t sequence=0;
    for(size_t i=0;i<shards.size();++i) {
        // Drop one data shard in every full 5-shard block; parity repairs it.
        if(i%5==0 && i+4<shards.size()) { ++sequence; continue; }
        std::vector<uint8_t> wire(4+shards[i].size());
        vstreamer::stream_header_write(wire.data(),{sequence++,true,true,0});
        std::copy(shards[i].begin(),shards[i].end(),wire.begin()+4);
        assert(receiver.ingest(wire.data(),wire.size()));
    }
    receiver.tick();
    fprintf(stderr,"frames=%u rtp=%zu shards=%zu bad=%llu\n",frames,rtp.size(),shards.size(),(unsigned long long)receiver.bad_packets);
    auto feedback=receiver.feedback();
    vstreamer::stream_link_report report{};
    assert(vstreamer::stream_link_report_decode(feedback.data()+4,feedback.size()-4,&report)==0);
    fprintf(stderr,"received=%llu gaps=%llu decoded=%llu fec_lost=%llu\n",(unsigned long long)report.counters.udp_packet_received,(unsigned long long)report.counters.udp_gap_count,(unsigned long long)report.counters.fec_packet_received,(unsigned long long)report.counters.fec_gap_count);
    assert(frames==1);
    assert(report.counters.udp_gap_count>0);
    assert(report.counters.fec_packet_received==rtp.size());
    assert(report.counters.fec_gap_count==0);
    uint8_t bad[]={0,0,0,0}; assert(!receiver.ingest(bad,sizeof(bad)));
    printf("Native RX: recovered %zu RTP packets, one matching IDR, feedback verified\n",rtp.size());
}
