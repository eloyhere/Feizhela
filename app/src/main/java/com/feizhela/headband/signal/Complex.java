package com.feizhela.headband.signal;

public final class Complex {
    private final double real;
    private final double imaginary;

    public Complex() {
        this.real = 0;
        this.imaginary = 0;
    }

    public Complex(double real, double imaginary) {
        this.real = real;
        this.imaginary = imaginary;
    }

    public double getReal() {
        return this.real;
    }

    public double getImaginary() {
        return this.imaginary;
    }

    public Angle argument(){
        return Angle.fromRadian(Math.atan2(this.getImaginary(), this.getReal()));
    }

    public double magnitude(){
        return Math.hypot(this.real, this.imaginary);
    }

    public Angle angular(final Complex other){
        return other.argument().minus(this.argument());
    }

    public Complex similarity(final Complex other){
        Angle diff = this.angular(other);
        return new Complex(diff.cosine(), diff.sine());
    }

    public Complex tendency(final Complex other){
        Angle diff = this.angular(other);
        double magnitude = this.magnitude() * other.magnitude();
        return new Complex(magnitude * diff.cosine(), magnitude * diff.sine());
    }

    public Complex conjugate(){
        return new Complex(this.getReal(), -this.getImaginary());
    }

    public Complex absolute(){
        return new Complex(Math.abs(this.getReal()), Math.abs(this.getImaginary()));
    }

    public Complex negate(){
        return new Complex(-this.getReal(), -this.getImaginary());
    }

    public Complex plus(final Complex other){
        return new Complex(this.getReal() + other.real, this.imaginary + other.getImaginary());
    }

    public Complex subtract(final Complex other){
        return new Complex(this.getReal() - other.real, this.imaginary - other.getImaginary());
    }

    public Complex multiply(final Complex other){
        return new Complex(real * other.real - imaginary * other.imaginary,
                real * other.imaginary + imaginary * other.real);
    }

    public Complex multiply(final double scalar){
        return new Complex(this.getReal() * scalar, this.getImaginary() * scalar);
    }

    public Complex rotate(final Angle angle){
        final Angle start = this.argument();
        final Angle destination = start.plus(angle);
        final double magnitude = this.magnitude();
        return new Complex(magnitude * destination.cosine(), magnitude * destination.sine());
    }

    public Complex divide(final Complex other){
        if (other.real == 0D && other.imaginary == 0D) {
            throw new ArithmeticException("Divide by zero");
        }
        // Normalize numerator and denominator separately so intermediate products
        // neither overflow nor underflow for finite, representable quotients.
        int numeratorExponent = Math.getExponent(Math.max(Math.abs(real), Math.abs(imaginary)));
        int denominatorExponent = Math.getExponent(Math.max(Math.abs(other.real), Math.abs(other.imaginary)));
        double a = Math.scalb(real, -numeratorExponent);
        double b = Math.scalb(imaginary, -numeratorExponent);
        double c = Math.scalb(other.real, -denominatorExponent);
        double d = Math.scalb(other.imaginary, -denominatorExponent);
        double denominator = c * c + d * d;
        int exponent = numeratorExponent - denominatorExponent;
        return new Complex(Math.scalb((a * c + b * d) / denominator, exponent),
                Math.scalb((b * c - a * d) / denominator, exponent));
    }

    public Complex divide(final double scalar){
        if (scalar == 0D) {
            throw new ArithmeticException("Divide by zero");
        }
        return new Complex(real / scalar, imaginary / scalar);
    }
}
