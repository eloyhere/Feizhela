package com.feizhela.headband.signal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class AngleEpicycleTest {
    private static final double EPSILON = 1e-10;

    @Test
    public void conversionsScaleValuesAndPreserveTheAngle(){
        assertEquals(Math.PI / 2, Angle.fromDegree(90).toRadian().getValue(), EPSILON);
        assertEquals(180, Angle.fromRadian(Math.PI).toDegree().getValue(), EPSILON);
        assertEquals(90, new Angle(0.25, 1).toDegree().getValue(), EPSILON);
        assertEquals(0.25, Angle.fromDegree(90).toUnit(1).getValue(), EPSILON);
        assertEquals(288, new Angle(8e307, 1e308).toDegree().getValue(), EPSILON);
        Angle radians = Angle.fromRadian(1);
        assertSame(radians, radians.toRadian());
    }

    @Test
    public void arithmeticConvertsOtherAngleAndKeepsTheLeftUnit(){
        Angle sum = Angle.fromDegree(90).plus(Angle.fromRadian(Math.PI / 2));
        Angle difference = Angle.fromDegree(180).minus(Angle.fromRadian(Math.PI / 2));
        assertEquals(180, sum.getValue(), EPSILON);
        assertEquals(360, sum.getUnit(), 0);
        assertEquals(90, difference.getValue(), EPSILON);
        assertEquals(270, Angle.fromDegree(0).minus(Angle.fromRadian(Math.PI / 2))
                .getValue(), EPSILON);
        Angle radians = Angle.fromRadian(Math.PI / 2).plus(Angle.fromDegree(90));
        assertEquals(Math.PI, radians.getValue(), EPSILON);
        assertEquals(2 * Math.PI, radians.getUnit(), 0);
        assertEquals(0.5, new Angle(0.25, 1).plus(Angle.fromDegree(90)).getValue(), EPSILON);
    }

    @Test
    public void normalizationWrapsNegativeAndMultiTurnAngles(){
        assertEquals(270, Angle.fromDegree(-90).getValue(), EPSILON);
        assertEquals(90, Angle.fromDegree(810).getValue(), EPSILON);
        assertEquals(0, Angle.fromDegree(360).getValue(), 0);
        assertEquals(0, Double.doubleToRawLongBits(Angle.fromDegree(-0D).getValue()));
        assertEquals(1e-16, Angle.fromRadian(1e-16).getValue(), 0);
        assertEquals(8e307, new Angle(8e307, 1e308).getValue(), 0);
    }

    @Test
    public void degreeTrigonometryUsesRadians(){
        assertEquals(1, Angle.fromDegree(90).sine(), EPSILON);
        assertEquals(-1, Angle.fromDegree(180).cosine(), EPSILON);
        assertEquals(1, Angle.fromDegree(45).tangent(), EPSILON);
        assertEquals(45, Angle.fromDegree(90).divide(2).getValue(), EPSILON);
        assertEquals(180, Angle.fromDegree(90).multiply(2).getValue(), EPSILON);
    }

    @Test
    public void invalidAngleValuesAndUnitsAreRejected(){
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> Angle.fromRadian(value));
        }
        for (double unit : new double[]{0, -0D, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Angle(0, unit));
            assertThrows(IllegalArgumentException.class, () -> Angle.fromDegree(90).toUnit(unit));
        }
        assertThrows(ArithmeticException.class, () -> Angle.fromDegree(1).divide(0D));
        assertThrows(ArithmeticException.class, () -> Angle.fromDegree(1).divide(-0D));
    }

    @Test
    public void defaultEpicycleHasUnitAmplitudeAndOneCyclePerUnit(){
        Epicycle epicycle = new Epicycle();
        assertEquals(1, epicycle.getAmplitude(), 0);
        assertEquals(2 * Math.PI, epicycle.getOmega(), 0);
        assertEquals(0, epicycle.getPhase().getValue(), 0);
        assertComplex(1, 0, epicycle.evaluate(0));
        assertComplex(0, 1, epicycle.evaluate(0.25));
        assertComplex(1, 0, epicycle.evaluate(1));
    }

    @Test
    public void evaluationSupportsDegreePhaseAndNegativeAmplitudeAndOmega(){
        for (double amplitude : new double[]{-2, 0, 2}) {
            for (double omega : new double[]{-3, 0, 3}) {
                Epicycle epicycle = new Epicycle(amplitude, omega, Angle.fromDegree(90));
                for (double x : new double[]{-0.4, 0, 0.4}) {
                    double angle = Math.PI / 2 + omega * x;
                    assertComplex(amplitude * Math.cos(angle), amplitude * Math.sin(angle),
                            epicycle.evaluate(x));
                    assertEquals(amplitude * Math.sin(angle), epicycle.sine(x), EPSILON);
                    assertEquals(amplitude * Math.cos(angle), epicycle.cosine(x), EPSILON);
                }
            }
        }
    }

    @Test
    public void tangentIsThePhaseTangentIndependentOfAmplitude(){
        assertEquals(1, new Epicycle(5, 0, Angle.fromDegree(45)).tangent(0), EPSILON);
        assertEquals(1, new Epicycle(0, 0, Angle.fromDegree(45)).tangent(1), EPSILON);
    }

    @Test
    public void derivativeMatchesAnalyticRealAndImaginaryComponents(){
        for (double omega : new double[]{-3, -0D, 0, 3}) {
            Epicycle epicycle = new Epicycle(2, omega, Angle.fromDegree(30));
            double x = -0.4;
            double angle = Math.PI / 6 + omega * x;
            double real = -2 * omega * Math.sin(angle);
            double imaginary = 2 * omega * Math.cos(angle);
            assertComplex(real, imaginary, epicycle.derivate(x));
            assertComplex(real, imaginary, epicycle.derivate().evaluate(x));
        }
    }

    @Test
    public void antiderivativeHasTheExpectedComponentsForEitherRotationDirection(){
        for (double omega : new double[]{-3, 3}) {
            Epicycle epicycle = new Epicycle(2, omega, Angle.fromDegree(30));
            double x = 0.7;
            double angle = omega * x + Math.PI / 6;
            assertComplex(2 / omega * Math.sin(angle), -2 / omega * Math.cos(angle),
                    epicycle.integral().evaluate(x));
            Complex expected = epicycle.evaluate(x);
            assertComplex(expected.getReal(), expected.getImaginary(),
                    epicycle.integral().derivate(x));
        }
    }

    @Test
    public void definiteIntegralMatchesAnIndependentAnalyticResult(){
        double start = -0.4;
        double end = 0.7;
        for (double omega : new double[]{-3, 3}) {
            Epicycle epicycle = new Epicycle(2, omega, Angle.fromDegree(30));
            double from = omega * start + Math.PI / 6;
            double to = omega * end + Math.PI / 6;
            double real = 2 / omega * (Math.sin(to) - Math.sin(from));
            double imaginary = 2 / omega * (Math.cos(from) - Math.cos(to));
            assertComplex(real, imaginary, epicycle.integral(start, end));
            assertComplex(-real, -imaginary, epicycle.integral(end, start));
            assertComplex(0, 0, epicycle.integral(start, start));
        }
    }

    @Test
    public void constantIntegralKeepsTheComplexPhase(){
        for (double omega : new double[]{0D, -0D}) {
            Epicycle constant = new Epicycle(2, omega, Angle.fromDegree(90));
            assertComplex(0, 4, constant.integral(0, 2));
            assertComplex(0, -4, constant.integral(2, 0));
            assertThrows(ArithmeticException.class, constant::integral);
        }
        Epicycle zero = new Epicycle(0, 0, Angle.fromDegree(90));
        assertComplex(0, 0, zero.integral(0, 2));
        assertComplex(0, 0, zero.integral().evaluate(1));
    }

    @Test
    public void invalidEpicycleArgumentsAreRejected(){
        assertThrows(NullPointerException.class, () -> new Epicycle(1, 2, null));
        Epicycle epicycle = new Epicycle();
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Epicycle(value, 1, new Angle()));
            assertThrows(IllegalArgumentException.class, () -> new Epicycle(1, value, new Angle()));
            assertThrows(IllegalArgumentException.class, () -> epicycle.evaluate(value));
            assertThrows(IllegalArgumentException.class, () -> epicycle.sine(value));
            assertThrows(IllegalArgumentException.class, () -> epicycle.cosine(value));
            assertThrows(IllegalArgumentException.class, () -> epicycle.tangent(value));
            assertThrows(IllegalArgumentException.class, () -> epicycle.derivate(value));
            assertThrows(IllegalArgumentException.class, () -> epicycle.integral(value, 0));
            assertThrows(IllegalArgumentException.class, () -> epicycle.integral(0, value));
        }
    }

    private static void assertComplex(double real, double imaginary, Complex actual){
        assertEquals(real, actual.getReal(), EPSILON);
        assertEquals(imaginary, actual.getImaginary(), EPSILON);
    }
}
