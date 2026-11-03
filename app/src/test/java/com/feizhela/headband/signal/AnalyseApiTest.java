package com.feizhela.headband.signal;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

/** Numerical baselines were captured from the original Analyse before kernel extraction. */
public class AnalyseApiTest {
    @Test public void calendarEstimateRetainsSeededGoldenParameters() {
        Analyse.Spectral.Result result = Analyse.Spectral.estimate(noisySignal(), 1000, 3);
        assertGolden(result, 1.8019163838105757e-6, new double[][] {
                {0.9998062866134032, 775.3457239530884, 0.2992667036277055},
                {0.5499420364801217, 1581.478144254961, 1.098506771958703},
                {0.2998982486231199, 1899.4056626849397, -0.6985583140048452}});
        assertTrue(result.isVerified());
    }

    @Test public void verificationRetryRetainsGoldenParameters() {
        Analyse.Spectral.Result result = Analyse.Spectral.estimate(noisySignal(), 1000, 3,
                new Analyse.Spectral.Options().withResidualThreshold(1e-8));
        assertGolden(result, 1.8018712951713605e-6, new double[][] {
                {0.9998062866133627, 775.3457237428341, 0.2992669136875564},
                {0.5499420364868095, 1581.4781445754593, 1.0985064519107797},
                {0.29989824883288074, 1899.4056626154104, -0.6985582437453625}});
        assertFalse(result.isVerified());
    }

    @Test public void allArrayDispatchesAgreeWithIndependentDftAndRoundTrip() {
        for (int length : new int[]{0,1,2,3,4,5,7,8,9,11,15,25,27,35,49,64,127}) {
            Complex[] input = complexSignal(length);
            Complex[] copy = input.clone();
            Complex[] expected = referenceDft(input, false);
            Complex[] forward = Sampler.forwardArray(input);
            assertComplexArray(expected, forward, 2e-9);
            assertComplexArray(expected, Sampler.mixedTransformArray(input, false), 2e-9);
            assertComplexArray(expected, Sampler.bluesteinTransformArray(input, false), 2e-9);
            assertComplexArray(expected, Sampler.discreteTransformArray(input, false), 2e-9);
            assertComplexArray(referenceDft(input, true), Sampler.inverseArray(input), 2e-9);
            assertComplexArray(input, Sampler.inverseArray(forward), 2e-9);
            assertComplexArray(copy, input, 0);
            assertNotSame(input, forward);
        }
    }

    @Test public void radixArraysRetainLengthRulesAndLegacyAdapters() {
        for (int radix : new int[]{2,3,5,7}) {
            int length = radix * radix;
            Complex[] input = complexSignal(length);
            Complex[] actual = Sampler.radixTransformArray(input, radix, false);
            assertComplexArray(referenceDft(input, false), actual, 2e-10);
            assertComplexArray(input, Sampler.radixTransformArray(actual, radix, true), 2e-10);
            Analyse.Sampler legacy = new Analyse.Sampler();
            legacy.setDirectData(input);
            Complex[] adapted = radix == 2 ? legacy.radix2() : radix == 3 ? legacy.radix3()
                    : radix == 5 ? legacy.radix5() : legacy.radix7();
            assertComplexArray(actual, adapted, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> Sampler.radixTransformArray(complexSignal(radix + 1), radix, false));
        }
        assertEquals(0, Sampler.radixTransformArray(new Complex[0], 2, false).length);
        assertThrows(IllegalArgumentException.class,
                () -> Sampler.radixTransformArray(new Complex[0], 3, false));
        assertThrows(IllegalArgumentException.class,
                () -> Sampler.radixTransformArray(complexSignal(8), 4, false));
    }

    @Test public void legacyArrayAdaptersPreserveAllDirectionsAndInputOwnership() {
        for (int length : new int[]{0,1,8,15,17}) {
            Complex[] input = complexSignal(length);
            Analyse.Sampler legacy = new Analyse.Sampler();
            legacy.setDirectData(input);
            for (boolean inverse : new boolean[]{false, true}) {
                assertComplexArray(Sampler.transformArray(input, inverse),
                        legacy.fastFourierTransform(inverse), 0);
                assertComplexArray(Sampler.mixedTransformArray(input, inverse),
                        legacy.mixedRadix(inverse), 0);
                assertComplexArray(Sampler.bluesteinTransformArray(input, inverse),
                        legacy.blueStein(inverse), 0);
                assertComplexArray(Sampler.discreteTransformArray(input, inverse),
                        legacy.discreteFourierTransform(inverse), 0);
            }
            assertComplexArray(input, legacy.gatherComplex(), 0);
        }
        assertThrows(NullPointerException.class, () -> Sampler.forwardArray(null));
        assertThrows(NullPointerException.class, () -> Sampler.forwardArray(new Complex[]{null}));
        // Legacy array transforms propagated nonfinite values rather than validating finiteness.
        assertTrue(Double.isNaN(Sampler.forwardArray(new Complex[]{new Complex(Double.NaN,0)})[0].getReal()));
    }

    @Test public void symmetricWindowsRetainSixTypesAndPeriodicDefaults() {
        for (Window.SymmetricType type : Window.SymmetricType.values()) {
            for (int length : new int[]{0,1,2,8,17}) {
                double[] weights = Window.symmetric(type, length);
                assertEquals(length, weights.length);
                assertArrayEquals(weights,
                        Analyse.Window.make(Analyse.Window.Type.valueOf(type.name()), length), 0);
                for (int i=0; i<length; i++) assertEquals(weights[i], weights[length-1-i], 1e-14);
                if (length == 1) assertEquals(1, weights[0], 0);
            }
        }
        assertArrayEquals(new double[]{0,.5,1,.5}, Window.HANN.coefficientsArray(4), 1e-15);
        assertArrayEquals(new double[]{0,.75,.75,0}, Window.symmetric(Window.SymmetricType.HANN,4), 1e-15);
        assertArrayEquals(new double[]{1,1,1,1}, Window.symmetric(Window.SymmetricType.KAISER,4,0), 0);
        assertEquals(427.56411572180474, Window.besselI0(8), 1e-11);
        assertThrows(IllegalArgumentException.class, () -> Window.HANN.coefficientsArray(0));
        assertThrows(IllegalArgumentException.class, () -> Window.symmetric(Window.SymmetricType.HANN,-1));
        assertThrows(NullPointerException.class, () -> Window.symmetric(null,4));
    }

    @Test public void toneAndOddHilbertEnvelopeRetainOriginalBaseline() {
        double[] signal = biasedTone();
        Tone tone = Tone.estimate(signal,64);
        assertEquals(2.299999999987287, tone.getAmplitude(), 1e-10);
        assertEquals(7.299999997861732, tone.getFrequency().hertz(), 1e-10);
        assertEquals(5.88318532056423, tone.getPhase().getValue(), 1e-10);
        assertEquals(.3449822432567736, tone.residualRatio(signal,64), 1e-12);
        double[] envelope = Envelope.fromSignal(signal,64).getOutgoingValues();
        assertEquals(2.405567084107908, envelope[0], 1e-11);
        assertEquals(2.5401826061210135, envelope[1], 1e-11);
        assertEquals(2.2820931120906183, envelope[63], 1e-11);
        assertEquals(1.011205892457977, envelope[126], 1e-11);
    }

    @Test public void evenHilbertEnvelopePreservesDcAndNyquistRules() {
        double[] signal = new double[128];
        for(int i=0;i<signal.length;i++) signal[i]=3+2*Math.cos(2*Math.PI*8*i/128);
        for(double amplitude : Envelope.fromSignal(signal,128).getOutgoingValues())
            assertEquals(2,amplitude,1e-12);
    }

    @Test public void publicFacadeUsesExplicitRatesAndImmutablePublicModels() {
        double[] raw = new double[1040];
        for(int i=0;i<raw.length;i++) raw[i]=Math.cos(2*Math.PI*1.3*i/104+.2)
                + .4*Math.cos(2*Math.PI*3.1*i/104-.3);
        Analyse.Spectral.Result legacy = Analyse.Spectral.estimateFromRaw(raw,104,20,3);
        Analyse.Result result = Analyse.estimateFromRaw(raw,104,20,3);
        assertEquals(legacy.getAnalysisWindow(),result.getAnalysisWindow());
        assertEquals(legacy.getResidualRatio(),result.getResidualRatio(),0);
        assertEquals(legacy.isVerified(),result.isVerified());
        assertEquals(legacy.getWaveform().size(),result.getWaveform().size());
        assertEquals(result.getWaveform().size(),result.getTones().size());
        for(int i=0;i<result.getWaveform().size();i++) {
            Analyse.Epicycle before=legacy.getWaveform().get(i);
            Epicycle after=result.getWaveform().getEpicycles().get(i);
            assertEquals(before.amplitude,after.getAmplitude(),0);
            assertEquals(before.omega,after.getOmega(),0);
            assertEquals(Angle.fromRadian(before.phase).getValue(),after.getPhase().getValue(),0);
        }
        for(double time : new double[]{0,.15,2,9}) {
            assertEquals(legacy.getWaveform().evaluate(time).getReal(),
                    result.getWaveform().evaluate(time).getReal(),1e-12);
        }
        assertThrows(UnsupportedOperationException.class, () -> result.getTones().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getWaveform().getEpicycles().clear());
        assertEquals(200,Analyse.Spectral.prepare(raw,104,20).length);
        assertEquals(200,Analyse.Spectral.prepare(raw,104,20,101).length);
    }

    @Test public void facadeRetainsShortSignalAndZeroComponentOutcome() {
        Analyse.Result shortResult = Analyse.estimate(new double[7],20,3);
        assertEquals(0,shortResult.getWaveform().size());
        assertEquals(0,shortResult.getAnalysisWindow());
        assertEquals(1,shortResult.getResidualRatio(),0);
        assertFalse(shortResult.isVerified());
        assertEquals(0,Analyse.estimate(new double[128],20,0).getWaveform().size());
    }

    @Test public void legacyNonFourierSemanticsRemainSeparate() {
        Analyse.Sampler sampler = new Analyse.Sampler();
        sampler.setDirectData(new Complex[]{new Complex(1,9),new Complex(3,8),new Complex(7,6)});
        assertComplexArray(new Complex[]{new Complex(4/Math.sqrt(2),0),
                new Complex(-2/Math.sqrt(2),0),new Complex(7,0)},sampler.waveletTransform(),0);
        assertComplexArray(new Complex[]{new Complex(2,0),new Complex(2,0),new Complex(-4,0)},
                sampler.laplacianTransform(),0);
        Analyse.Epicycle constant = new Analyse.Epicycle(2,0,.4);
        assertEquals(0,constant.integral().amplitude,0);
        assertEquals(0,constant.derivate(1,2).magnitude(),0);
        assertEquals(0,new Analyse.Sample(0,0).pointCount());
    }

    private static void assertGolden(Analyse.Spectral.Result result,double residual,double[][] components) {
        assertEquals(1482,result.getAnalysisWindow());
        assertEquals(residual,result.getResidualRatio(),1e-14);
        assertEquals(components.length,result.getWaveform().size());
        for(int i=0;i<components.length;i++) {
            Analyse.Epicycle actual=result.getWaveform().get(i);
            assertEquals(components[i][0],actual.amplitude,1e-10);
            assertEquals(components[i][1],actual.omega,1e-7);
            assertEquals(components[i][2],actual.phase,1e-7);
        }
    }
    private static double[] noisySignal() {
        double[] result=new double[2000]; Random random=new Random(42L);
        for(int i=0;i<result.length;i++) {
            double t=i/1000.0;
            result[i]=Math.cos(2*Math.PI*123.4*t+.3)+.55*Math.cos(2*Math.PI*251.7*t+1.1)
                    +.3*Math.cos(2*Math.PI*302.3*t-.7)+random.nextGaussian()*.001;
        }
        return result;
    }
    private static double[] biasedTone() {
        double[] result=new double[127];
        for(int i=0;i<result.length;i++) result[i]=2.3*Math.cos(2*Math.PI*7.3*i/64-.4)+1.2;
        return result;
    }
    private static Complex[] complexSignal(int n) {
        Complex[] result=new Complex[n];
        for(int i=0;i<n;i++) result[i]=new Complex(Math.sin(.73*i)+.03*i,Math.cos(.29*i)-.01*i);
        return result;
    }
    private static Complex[] referenceDft(Complex[] input,boolean inverse) {
        Complex[] result=new Complex[input.length];
        for(int k=0;k<input.length;k++) {
            double re=0,im=0;
            for(int j=0;j<input.length;j++) {
                double angle=(inverse?1:-1)*2*Math.PI*k*j/input.length;
                re+=input[j].getReal()*Math.cos(angle)-input[j].getImaginary()*Math.sin(angle);
                im+=input[j].getReal()*Math.sin(angle)+input[j].getImaginary()*Math.cos(angle);
            }
            result[k]=new Complex(inverse?re/input.length:re,inverse?im/input.length:im);
        }
        return result;
    }
    private static void assertComplexArray(Complex[] expected,Complex[] actual,double tolerance) {
        assertEquals(expected.length,actual.length);
        for(int i=0;i<expected.length;i++) {
            assertEquals("real at "+i,expected[i].getReal(),actual[i].getReal(),tolerance);
            assertEquals("imaginary at "+i,expected[i].getImaginary(),actual[i].getImaginary(),tolerance);
        }
    }
}
