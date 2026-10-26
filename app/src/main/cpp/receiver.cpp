#include "receiver.hpp"
#include <utility>

namespace rover {
Receiver::Receiver(Frame cb): output(std::move(cb)) {}
bool Receiver::ingest(const uint8_t* bytes,size_t len) {
    if (!bytes || len < 12 || len > 1445) { ++bad_packets; return false; }
    std::vector<uint8_t> au;
    const int result = depay.feed(bytes, len, &au);
    if (result < 0) { ++bad_packets; return false; }
    if (result > 0) {
        ++frames;
        output(au, depay.au_pts()*1000000/30, depay.au_key());
    }
    return true;
}
}
