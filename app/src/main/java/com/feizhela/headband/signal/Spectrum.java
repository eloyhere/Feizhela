package com.feizhela.headband.signal;

import java.util.List;

/** 完整双边频谱，前向变换不归一化，频率单位为 Hz。 */
public final class Spectrum {
    private final double sampleRate;
    private final List<Complex> coefficients;

    public Spectrum(double sampleRate, Complex[] coefficients) {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive and finite");
        }
        SignalMath.validate(coefficients);
        if (coefficients.length == 0) throw new IllegalArgumentException("spectrum cannot be empty");
        this.sampleRate = sampleRate;
        this.coefficients = SignalMath.list(coefficients);
    }

    public Spectrum(double sampleRate, List<Complex> coefficients) {
        this(sampleRate, SignalMath.array(coefficients));
    }

    public double getSampleRate() { return sampleRate; }
    public int size() { return coefficients.size(); }
    public Complex get(int index) { return coefficients.get(index); }
    public List<Complex> getCoefficients() { return coefficients; }
    public Complex[] toArray() { return coefficients.toArray(new Complex[0]); }

    /** 双边频率轴；后半段为负频率，偶数长度的 Nyquist 点记为正值。 */
    public double frequencyAt(int index) {
        checkIndex(index);
        return ((double) (index <= size() / 2 ? index : index - size()) / size()) * sampleRate;
    }

    public double signedFrequencyAt(int index) { return frequencyAt(index); }

    /** 原始 DFT 索引对应的未折返频率：k * sampleRate / N。 */
    public double rawFrequencyAt(int index) {
        checkIndex(index);
        return ((double) index / size()) * sampleRate;
    }

    public List<Complex> inverse() { return Sampler.inverseFourier(coefficients); }

    private void checkIndex(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("index");
    }
}
