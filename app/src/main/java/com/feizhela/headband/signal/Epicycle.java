package com.feizhela.headband.signal;

/**
 * A rotating complex vector: A * exp(i * (omega * x + phase)).
 * If x is measured in seconds, omega is measured in radians per second.
 * Negative amplitude and omega are supported.
 */
public final class Epicycle {

    private final double amplitude;

    private final double omega;

    private final Angle phase;

    public Epicycle(){
        this(1D, Math.PI * 2, Angle.fromRadian(0D));
    }

    public Epicycle(final double amplitude, final double omega, final Angle phase){
        if (!Double.isFinite(amplitude)) {
            throw new IllegalArgumentException("amplitude must be finite");
        }
        if (!Double.isFinite(omega)) {
            throw new IllegalArgumentException("omega must be finite");
        }
        if (phase == null) {
            throw new NullPointerException("phase must not be null");
        }
        this.amplitude = amplitude;
        this.omega = omega;
        this.phase = phase;
    }

    public double getAmplitude(){
        return amplitude;
    }

    public double getOmega(){
        return omega;
    }

    public Angle getPhase(){
        return phase;
    }

    /** Returns the imaginary component A * sin(omega * x + phase). */
    public double sine(final double x){
        return evaluate(x).getImaginary();
    }

    /** Returns the real component A * cos(omega * x + phase). */
    public double cosine(final double x){
        return evaluate(x).getReal();
    }

    /** Returns tan(omega * x + phase), independent of amplitude. */
    public double tangent(final double x){
        return angleAt(x).tangent();
    }

    public Complex evaluate(final double x){
        final Angle angle = angleAt(x);
        return new Complex(amplitude * angle.cosine(), amplitude * angle.sine());
    }

    private Angle angleAt(final double x){
        if (!Double.isFinite(x)) {
            throw new IllegalArgumentException("x must be finite");
        }
        return phase.plus(Angle.fromRadian(omega * x));
    }

    public Epicycle derivate(){
        if (omega == 0D) {
            return new Epicycle(0D, 0D, phase);
        }
        return new Epicycle(amplitude * omega, omega,
                phase.plus(Angle.fromRadian(Math.PI / 2)));
    }

    /** Returns the analytic derivative evaluated at x. */
    public Complex derivate(final double x){
        return derivate().evaluate(x);
    }

    /** Returns an antiderivative with zero integration constant. */
    public Epicycle integral(){
        if (amplitude == 0D) {
            return new Epicycle(0D, omega, phase);
        }
        if (omega == 0D) {
            throw new ArithmeticException("A nonzero constant has no epicycle-form antiderivative");
        }
        return new Epicycle(amplitude / omega, omega,
                phase.minus(Angle.fromRadian(Math.PI / 2)));
    }

    public Complex integral(final double start, final double end){
        if (!Double.isFinite(start) || !Double.isFinite(end)) {
            throw new IllegalArgumentException("integration bounds must be finite");
        }
        if (start == end || amplitude == 0D) {
            return new Complex();
        }
        if (omega == 0D) {
            final double scale = amplitude * (end - start);
            return new Complex(scale * phase.cosine(), scale * phase.sine());
        }
        final Epicycle antiderivative = integral();
        return antiderivative.evaluate(end).subtract(antiderivative.evaluate(start));
    }
}
