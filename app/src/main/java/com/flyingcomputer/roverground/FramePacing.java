package com.flyingcomputer.roverground;

import java.util.ArrayDeque;

/** Decoded-frame interval variation over the last five seconds, using a monotonic clock. */
final class FramePacing {
    private record Interval(long endNs, double ms) {}
    private final ArrayDeque<Interval> intervals = new ArrayDeque<>();
    private final ArrayDeque<Long> frames = new ArrayDeque<>();
    private final long startNs;
    FramePacing(long startNs) { this.startNs = startNs; }
    private long lastNs = Long.MIN_VALUE;

    synchronized void frame(long nowNs) {
        if (lastNs != Long.MIN_VALUE) intervals.addLast(new Interval(nowNs, (nowNs-lastNs)/1e6));
        lastNs = nowNs;
        frames.addLast(nowNs);
        prune(nowNs);
    }

    private void prune(long nowNs) {
        while (!frames.isEmpty() && frames.peekFirst() <= nowNs-5_000_000_000L) frames.removeFirst();
        while (!intervals.isEmpty() && intervals.peekFirst().endNs() <= nowNs-5_000_000_000L)
            intervals.removeFirst();
    }

    /** Nominal rate shortfall over the last five seconds (or time since creation). */
    synchronized double decodeErrorFps(long nowNs, double nominalFps) {
        prune(nowNs);
        double seconds = Math.min(5, (nowNs-startNs)/1e9);
        return seconds > 0 ? Math.max(0, nominalFps-frames.size()/seconds) : 0;
    }

    /** Mean absolute deviation from the rolling mean interval; -1 without an interval. */
    synchronized double errorMs(long nowNs) {
        prune(nowNs);
        if (intervals.isEmpty()) return -1;
        double mean = 0, error = 0;
        for (Interval i : intervals) mean += i.ms();
        mean /= intervals.size();
        for (Interval i : intervals) error += Math.abs(i.ms()-mean);
        return error/intervals.size();
    }
}
