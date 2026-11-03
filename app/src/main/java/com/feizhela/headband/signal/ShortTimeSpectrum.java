package com.feizhela.headband.signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable STFT frames and their start times. */
public final class ShortTimeSpectrum {
    private final List<Double> frameTimes;
    private final List<Spectrum> frames;

    public ShortTimeSpectrum(List<Double> frameTimes, List<Spectrum> frames) {
        Objects.requireNonNull(frameTimes, "frameTimes");
        Objects.requireNonNull(frames, "frames");
        if (frameTimes.isEmpty() || frameTimes.size() != frames.size())
            throw new IllegalArgumentException("frame arrays must be nonempty and equal length");
        ArrayList<Double> times = new ArrayList<>(frameTimes.size());
        for (Double time : frameTimes) {
            if (time == null || !Double.isFinite(time)) throw new IllegalArgumentException("invalid frame time");
            times.add(time);
        }
        ArrayList<Spectrum> spectra = new ArrayList<>(frames.size());
        for (Spectrum frame : frames) spectra.add(Objects.requireNonNull(frame, "frame"));
        this.frameTimes = Collections.unmodifiableList(times);
        this.frames = Collections.unmodifiableList(spectra);
    }

    public ShortTimeSpectrum(double[] frameTimes, Spectrum[] frames) {
        this(toList(frameTimes), java.util.Arrays.asList(Objects.requireNonNull(frames, "frames").clone()));
    }

    private static List<Double> toList(double[] values) {
        Objects.requireNonNull(values, "frameTimes");
        ArrayList<Double> result = new ArrayList<>(values.length);
        for (double value : values) result.add(value);
        return result;
    }

    public int getFrameCount() { return frames.size(); }
    public double getFrameTime(int index) { return frameTimes.get(index); }
    public Spectrum getFrame(int index) { return frames.get(index); }
    public List<Double> getFrameTimes() { return frameTimes; }
    public List<Spectrum> getFrames() { return frames; }
}
