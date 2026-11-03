package com.feizhela.headband.signal;

public final class Sample {

    private final double startTime;
    private final double duration;
    private final double rate;
    private final int count;

    public Sample(double duration, double rate) {
        this(0D, duration, rate);
    }

    public Sample(double startTime, double duration, double rate) {
        if (!Double.isFinite(startTime)) {
            throw new IllegalArgumentException("startTime must be finite");
        }
        if (!Double.isFinite(duration) || duration <= 0D) {
            throw new IllegalArgumentException("duration must be positive and finite");
        }
        if (!Double.isFinite(rate) || rate <= 0D) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
        long sampleCount = Math.round(duration * rate);
        if (sampleCount < 1 || sampleCount > Integer.MAX_VALUE
                || !Double.isFinite(startTime + (sampleCount - 1D) / rate)) {
            throw new IllegalArgumentException("duration * rate must round to a positive int");
        }
        this.startTime = startTime;
        this.duration = duration;
        this.rate = rate;
        this.count = (int) sampleCount;
    }

    public double getStartTime() { return startTime; }
    public double getDuration() { return duration; }
    public double getRate() { return rate; }
    public int getCount() { return count; }

    public double timeAt(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException("index");
        return startTime + index / rate;
    }
}
