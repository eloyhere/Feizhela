package com.feizhela.headband.signal;

/** Periodic analysis windows, appropriate for FFT frames. */
public final class Window {
    public enum Type { RECTANGULAR, HANN, HAMMING }
    public static final Window RECTANGULAR = new Window(Type.RECTANGULAR);
    public static final Window HANN = new Window(Type.HANN);
    public static final Window HAMMING = new Window(Type.HAMMING);
    private final Type type;

    public Window() { this(Type.HANN); }
    public Window(Type type) { this.type = java.util.Objects.requireNonNull(type, "type"); }
    public Type getType() { return type; }

    public java.util.List<Double> coefficients(int length) {
        double[] values = coefficientsArray(length);
        java.util.ArrayList<Double> result = new java.util.ArrayList<>(values.length);
        for (double value : values) result.add(value);
        return java.util.Collections.unmodifiableList(result);
    }

    public double[] coefficientsArray(int length) {
        if (length < 1) throw new IllegalArgumentException("length must be positive");
        double[] result = new double[length];
        for (int n = 0; n < length; n++) {
            double c = Math.cos(2 * Math.PI * n / length);
            result[n] = length == 1 || type == Type.RECTANGULAR ? 1D
                    : type == Type.HANN ? 0.5 - 0.5 * c : 0.54 - 0.46 * c;
        }
        return result;
    }

    public java.util.List<Complex> apply(java.util.List<Complex> input) {
        return SignalMath.list(apply(SignalMath.array(input)));
    }

    public Complex[] apply(Complex[] input) {
        SignalMath.validate(input);
        double[] weights = coefficientsArray(input.length);
        Complex[] result = new Complex[input.length];
        for (int i = 0; i < result.length; i++) result[i] = input[i].multiply(weights[i]);
        return result;
    }

    /** Symmetric analysis windows, with angular denominator length - 1. */
    public enum SymmetricType { RECTANGULAR, HANN, HAMMING, BLACKMAN, KAISER, FLAT_TOP }

    /** Modified Bessel I0, using the legacy 64-term expansion. */
    public static double besselI0(final double value) {
            double sum = 1.0;
            double term = 1.0;
            final double halfSquare = value * value / 4.0;
            for (int order = 1; order < 64; order++) {
                term *= halfSquare / ((double) order * order);
                sum += term;
                if (term < 1e-16 * sum) {
                    break;
                }
            }
            return sum;
        }

    /** Generates a symmetric window; Kaiser beta defaults to 8. Empty windows are supported. */
    public static double[] symmetric(SymmetricType type, int length) {
        return symmetric(type, length, 8.0);
    }

    /** Generates symmetric coefficients without changing periodic instance defaults. */
    public static double[] symmetric(final SymmetricType type, final int length, final double parameter) {
            java.util.Objects.requireNonNull(type, "type");
            if (length < 0) {
                throw new IllegalArgumentException("length must not be negative");
            }
            final double[] window = new double[length];
            java.util.Arrays.fill(window, 1.0);
            if (length <= 1) {
                return window;
            }
            final double denominator = (double) (length - 1);
            for (int index = 0; index < length; index++) {
                final double angle = 2.0 * Math.PI * index / denominator;
                switch (type) {
                    case RECTANGULAR:
                        window[index] = 1.0;
                        break;
                    case HANN:
                        window[index] = 0.5 - 0.5 * Math.cos(angle);
                        break;
                    case HAMMING:
                        window[index] = 0.54 - 0.46 * Math.cos(angle);
                        break;
                    case BLACKMAN:
                        window[index] = 0.42
                                - 0.5 * Math.cos(angle)
                                + 0.08 * Math.cos(2.0 * angle);
                        break;
                    case KAISER: {
                        final double normalized = 2.0 * index / denominator - 1.0;
                        final double inner = Math.sqrt(Math.max(0.0,
                                1.0 - normalized * normalized));
                        window[index] = besselI0(parameter * inner) / besselI0(parameter);
                        break;
                    }
                    case FLAT_TOP:
                        window[index] = 0.21557895
                                - 0.41663158 * Math.cos(angle)
                                + 0.277263158 * Math.cos(2.0 * angle)
                                - 0.083578947 * Math.cos(3.0 * angle)
                                + 0.006947368 * Math.cos(4.0 * angle);
                        break;
                    default:
                        window[index] = 1.0;
                        break;
                }
            }
            return window;
        }
}
