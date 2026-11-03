package com.feizhela.headband.signal;

/** One coefficient of a parameterized integral transform. */
public final class Spectral {
    private final Complex parameter;
    private final Complex coefficient;
    public Spectral(Complex parameter, Complex coefficient) {
        this.parameter = java.util.Objects.requireNonNull(parameter, "parameter");
        this.coefficient = java.util.Objects.requireNonNull(coefficient, "coefficient");
    }
    public Complex getParameter() { return parameter; }
    public Complex getCoefficient() { return coefficient; }
    public Complex getValue() { return coefficient; }
}
