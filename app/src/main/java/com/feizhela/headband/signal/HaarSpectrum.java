package com.feizhela.headband.signal;

import java.util.List;

/** Haar coefficients with the original and zero-padded lengths. */
public final class HaarSpectrum {
    private final List<Complex> coefficients;
    private final int originalLength;
    private final int levels;

    public HaarSpectrum(Complex[] coefficients, int originalLength, int levels) {
        SignalMath.validate(coefficients);
        if (coefficients.length == 0 || originalLength < 1 || originalLength > coefficients.length
                || (coefficients.length & (coefficients.length - 1)) != 0
                || levels < 0 || levels > Integer.numberOfTrailingZeros(coefficients.length)) {
            throw new IllegalArgumentException("invalid Haar coefficients or dimensions");
        }
        this.coefficients = SignalMath.list(coefficients);
        this.originalLength = originalLength;
        this.levels = levels;
    }

    public HaarSpectrum(List<Complex> coefficients, int originalLength, int levels) {
        this(SignalMath.array(coefficients), originalLength, levels);
    }

    public List<Complex> getCoefficients() { return coefficients; }
    public int getOriginalLength() { return originalLength; }
    public int getPaddedLength() { return coefficients.size(); }
    public int getLevels() { return levels; }

    public List<Complex> inverse() {
        Complex[] current = coefficients.toArray(new Complex[0]);
        int active = current.length >> levels;
        for (int level = 0; level < levels; level++) {
            Complex[] expanded = current.clone();
            for (int i = 0; i < active; i++) {
                Complex average = current[i];
                Complex detail = current[active + i];
                expanded[2 * i] = average.plus(detail).multiply(1 / Math.sqrt(2D));
                expanded[2 * i + 1] = average.subtract(detail).multiply(1 / Math.sqrt(2D));
            }
            System.arraycopy(expanded, 0, current, 0, active * 2);
            active *= 2;
        }
        Complex[] original = java.util.Arrays.copyOf(current, originalLength);
        return SignalMath.list(original);
    }
}
