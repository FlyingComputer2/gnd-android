#include "receiver.hpp"
#include <cassert>
#include <cstdio>

int main() {
    std::vector<uint8_t> au{0,0,0,1,0x65}; au.resize(3005,0x55);
    vstreamer::rtp_h264_packer packer({300,96,0x1234,30});
    unsigned frames=0;
    int64_t expected_pts=0;
    rover::Receiver receiver([&](const auto& frame,int64_t pts,bool key) {
        assert(frame==au); assert(key); assert(pts==expected_pts); ++frames;
    });
    uint8_t packet[1600];
    for(int frame=0;frame<3;++frame) {
        expected_pts=int64_t(frame)*1000000;
        assert(packer.pack_annexb(au.data(),au.size(),frame*30)>=0);
        unsigned packets=0;
        while(packer.pending()) {
            int n=packer.pop_datagram(packet,sizeof(packet)); assert(n>0);
            assert(receiver.ingest(packet,n)); ++packets;
        }
        assert(packets>1 && frames==unsigned(frame+1));
    }
    uint8_t bad[12]{}; assert(!receiver.ingest(bad,sizeof(bad)));
    assert(!receiver.ingest(nullptr,100));
    assert(!receiver.ingest(packet,1446));
    // Old vstreamer envelopes must not be accepted as recovered RTP.
    uint8_t old[16]{}; old[2]=0x80; assert(!receiver.ingest(old,sizeof(old)));
    assert(receiver.bad_packets==4);
    printf("Android RTP RX: three matching fragmented IDRs, timestamps, invalid/legacy rejection passed\n");
}
