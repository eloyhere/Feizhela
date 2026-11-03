package com.feizhela.headband.signal;

public final class Angle {

    private final double value;

    private final double unit;

    private static final double Pi = Math.PI;

    private static final double DoublePi = Math.PI * 2;

    private static final double HalfPi = Math.PI * 0.5;

    private static final double ThreeHundredSixty = 360;

    public Angle(){
        this.unit = Math.PI * 2;
        this.value = 0;
    }

    public static Angle fromRadian(final double value){
        return new Angle(value, Math.PI * 2);
    }

    public static Angle fromDegree(final double value){
        return new Angle(value, 360D);
    }

    public static Angle arcsine(final double value) {
        if (Double.isNaN(value) || value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException("value must be in [-1, 1]");
        }
        return fromRadian(Math.asin(value));
    }

    public static Angle arccosine(final double value) {
        if (Double.isNaN(value) || value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException("value must be in [-1, 1]");
        }
        return fromRadian(Math.acos(value));
    }

    public static Angle arctangent(final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        return fromRadian(Math.atan(value));
    }

    public static Angle arctangent2(final double y, final double x) {
        if (!Double.isFinite(y) || !Double.isFinite(x)) {
            throw new IllegalArgumentException("y and x must be finite");
        }
        if (x == 0.0 && y == 0.0) {
            throw new IllegalArgumentException("arctangent2(0, 0) is undefined");
        }
        return fromRadian(Math.atan2(y, x));
    }

    public Angle(final double value, final double unit) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        if (!Double.isFinite(unit) || unit <= 0.0) {
            throw new IllegalArgumentException("unit must be positive finite");
        }
        this.unit = unit;
        final double remainder = value % unit;
        final double normalized = remainder < 0D ? remainder + unit : remainder;
        this.value = normalized == 0D || normalized >= unit ? 0D : normalized;
    }

    public double getValue() {
        return value;
    }

    public double getUnit() {
        return unit;
    }

    public Angle plus(final Angle other){
        final Angle converted = other.toUnit(this.unit);
        return new Angle(this.value + converted.value, this.unit);
    }

    public Angle minus(final Angle other){
        final Angle converted = other.toUnit(this.unit);
        return new Angle(this.value - converted.value, this.unit);
    }

    public Angle multiply(final double other){
        return new Angle(this.getValue() * other, this.getUnit());
    }

    public Angle divide(final double other){
        if(other == 0D){
            throw new ArithmeticException("Divide by zero");
        }
        return new Angle(this.getValue() / other, this.getUnit());
    }

    public double sine(){
        return Math.sin(this.toRadian().getValue());
    }

    public double cosine(){
        return Math.cos(this.toRadian().getValue());
    }

    public double tangent(){
        return Math.tan(this.toRadian().getValue());
    }

    public boolean isDegree(){
        return Double.compare(this.unit, ThreeHundredSixty) == 0;
    }

    public Angle toDegree(){
        if(this.isDegree()){
            return this;
        }
        return this.toUnit(ThreeHundredSixty);
    }

    public boolean isRadian(){
        return Double.compare(this.unit, Math.PI * 2) == 0;
    }

    public Angle toRadian(){
        return this.toUnit(Math.PI * 2);
    }

    public Angle toUnit(final double targetUnit) {
        if (!Double.isFinite(targetUnit) || targetUnit <= 0.0) {
            throw new IllegalArgumentException("targetUnit must be positive finite");
        }
        if (Double.compare(this.unit, targetUnit) == 0) {
            return this;
        }
        return new Angle((this.value / this.unit) * targetUnit, targetUnit);
    }
}
