package com.feizhela.headband.signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A finite sum of rotating complex vectors. */
public final class Waveform {
    private final List<Epicycle> epicycles;

    public Waveform() { this(Collections.emptyList()); }
    public Waveform(Iterable<Epicycle> epicycles) {
        if (epicycles == null) throw new NullPointerException("epicycles");
        ArrayList<Epicycle> copy = new ArrayList<>();
        for (Epicycle epicycle : epicycles) copy.add(java.util.Objects.requireNonNull(epicycle, "epicycle"));
        this.epicycles = Collections.unmodifiableList(copy);
    }
    public List<Epicycle> getEpicycles() { return epicycles; }
    public int size() { return epicycles.size(); }

    public Complex evaluate(double x) {
        SignalMath.finite(x, "x");
        double real = 0D, imaginary = 0D;
        for (Epicycle epicycle : epicycles) {
            Complex value = epicycle.evaluate(x);
            real += value.getReal();
            imaginary += value.getImaginary();
        }
        return new Complex(real, imaginary);
    }

    public double sine(double x) { return evaluate(x).getImaginary(); }
    public double cosine(double x) { return evaluate(x).getReal(); }
    public Waveform sine() { return project(true); }
    public Waveform cosine() { return project(false); }
    public double tangent(double x) {
        Complex value = evaluate(x);
        if (value.getReal() == 0D) throw new ArithmeticException("Waveform real component is zero");
        return value.getImaginary() / value.getReal();
    }

    private Waveform project(boolean imaginaryProjection) {
        ArrayList<Epicycle> result = new ArrayList<>(epicycles.size() * 2);
        for (Epicycle epicycle : epicycles) {
            double amplitude = epicycle.getAmplitude() / 2D;
            Angle phase = epicycle.getPhase();
            double omega = epicycle.getOmega();
            if (imaginaryProjection) {
                result.add(new Epicycle(amplitude, omega, phase.minus(Angle.fromRadian(Math.PI / 2))));
                result.add(new Epicycle(amplitude, -omega, phase.multiply(-1).plus(Angle.fromRadian(Math.PI / 2))));
            } else {
                result.add(new Epicycle(amplitude, omega, phase));
                result.add(new Epicycle(amplitude, -omega, phase.multiply(-1)));
            }
        }
        return new Waveform(result);
    }

    public Waveform derivate() {
        ArrayList<Epicycle> result = new ArrayList<>(epicycles.size());
        for (Epicycle epicycle : epicycles) result.add(epicycle.derivate());
        return new Waveform(result);
    }
    public Complex derivate(double x) { return derivate().evaluate(x); }

    public Waveform integral() {
        ArrayList<Epicycle> result = new ArrayList<>();
        double constantReal = 0D, constantImaginary = 0D;
        for (Epicycle epicycle : epicycles) {
            if (epicycle.getOmega() == 0D) {
                Complex value = epicycle.evaluate(0D);
                constantReal += value.getReal();
                constantImaginary += value.getImaginary();
            } else {
                result.add(epicycle.integral());
            }
        }
        if (constantReal != 0D || constantImaginary != 0D) {
            throw new ArithmeticException("A nonzero constant waveform has a linear antiderivative");
        }
        return new Waveform(result);
    }
    public Complex integral(double start, double end) {
        SignalMath.finite(start, "start");
        SignalMath.finite(end, "end");
        double real = 0D, imaginary = 0D;
        for (Epicycle epicycle : epicycles) {
            Complex value = epicycle.integral(start, end);
            real += value.getReal();
            imaginary += value.getImaginary();
        }
        return new Complex(real, imaginary);
    }

    public List<Complex> sample(Sample sample) {
        java.util.Objects.requireNonNull(sample, "sample");
        ArrayList<Complex> result = new ArrayList<>(sample.getCount());
        for (int i = 0; i < sample.getCount(); i++) result.add(evaluate(sample.timeAt(i)));
        return Collections.unmodifiableList(result);
    }

    public Complex[] sampleArray(Sample sample) {
        return sample(sample).toArray(new Complex[0]);
    }
}
