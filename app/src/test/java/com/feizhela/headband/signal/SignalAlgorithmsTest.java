package com.feizhela.headband.signal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SignalAlgorithmsTest {
    private static final double EPS = 1e-9;

    @Test
    public void sampleUsesRoundedCountAndStartsAtConfiguredTime() {
        Sample sample = new Sample(2.5, 1.01, 4);
        assertEquals(4, sample.getCount()); // round(1.01 * 4)
        assertEquals(2.5, sample.timeAt(0), 0);
        assertEquals(3.25, sample.timeAt(3), 0);
        assertThrows(IndexOutOfBoundsException.class, () -> sample.timeAt(4));
        assertThrows(IndexOutOfBoundsException.class, () -> sample.timeAt(-1));
        assertEquals(5, new Sample(0, 1.125, 4).getCount());
        assertEquals(1.01, sample.getDuration(), 0);
        assertEquals(4, sample.getRate(), 0);
    }

    @Test
    public void sampleRejectsNonFiniteAndUnrepresentableConfiguration() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Sample(value, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new Sample(0, value, 1));
            assertThrows(IllegalArgumentException.class, () -> new Sample(0, 1, value));
        }
        assertThrows(IllegalArgumentException.class, () -> new Sample(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Sample(0, 0.1, 1));
    }

    @Test
    public void waveformIsTheComplexSumAndProjectionProducesRealComponents() {
        Waveform waveform = waveform(2, 3, 0.3, -0.75, 1.2, -0.4);
        for (double x : new double[]{-0.2, 0, 0.37, 1.1}) {
            Complex sum = waveform.getEpicycles().get(0).evaluate(x)
                    .plus(waveform.getEpicycles().get(1).evaluate(x));
            assertComplex(sum, waveform.evaluate(x));
            assertEquals(waveform.evaluate(x).getImaginary(), waveform.sine(x), EPS);
            assertEquals(waveform.evaluate(x).getReal(), waveform.cosine(x), EPS);
            assertEquals(waveform.evaluate(x).getImaginary(), waveform.sine().evaluate(x).getReal(), EPS);
            assertEquals(waveform.evaluate(x).getReal(), waveform.cosine().evaluate(x).getReal(), EPS);
            assertEquals(waveform.evaluate(x).getImaginary() / waveform.evaluate(x).getReal(),
                    waveform.tangent(x), EPS);
        }
        assertThrows(ArithmeticException.class, () -> new Waveform().tangent(0));
    }

    @Test
    public void cancellingConstantEpicyclesHaveFiniteZeroIntegralAndDerivative() {
        Waveform cancel = new Waveform(Arrays.asList(
                new Epicycle(3, 0, Angle.fromRadian(0.7)),
                new Epicycle(-3, 0, Angle.fromRadian(0.7))));
        assertComplex(new Complex(), cancel.evaluate(0.2));
        assertComplex(new Complex(), cancel.derivate(0.2));
        assertComplex(new Complex(), cancel.integral(0, 4));
        assertComplex(new Complex(), cancel.integral().evaluate(0.2));

        Waveform oscillation = new Waveform(Collections.singletonList(
                new Epicycle(2, 3, Angle.fromRadian(0.4))));
        for (double x : new double[]{-0.5, 0, 0.8}) {
            assertComplex(oscillation.getEpicycles().get(0).derivate(x), oscillation.derivate(x));
            assertComplex(oscillation.evaluate(x), oscillation.integral().derivate(x));
        }
    }

    @Test
    public void complexMultiplicationAndDivisionUseBothComponents() {
        Complex a = new Complex(3, 4);
        Complex b = new Complex(2, -5);
        assertComplex(new Complex(26, -7), a.multiply(b));
        assertComplex(new Complex(-14.0 / 29, 23.0 / 29), a.divide(b));
        assertComplex(a, a.divide(b).multiply(b));
        assertThrows(ArithmeticException.class, () -> a.divide(new Complex()));
    }

    @Test
    public void dftMatchesIndependentFullComplexReferenceAndUnnormalizedConvention() {
        Sample sample = new Sample(-0.25, 1, 8);
        Waveform input = waveform(0.7, 1.9, -0.2, -1.1, -3.5, 0.6);
        Spectrum spectrum = new Sampler(sample).discreteFourierTransform(input);
        Complex[] values = input.sampleArray(sample);
        assertSpectrumMatches(dft(values), spectrum);
        assertComplex(new Complex(sumReal(values), sumImaginary(values)), spectrum.get(0));
        assertEquals(8, spectrum.size());
        assertEquals(0, spectrum.frequencyAt(0), 0);
        assertEquals(-1, spectrum.frequencyAt(7), EPS);
        assertEquals(7, spectrum.rawFrequencyAt(7), EPS);
        assertEquals(-1, spectrum.signedFrequencyAt(7), EPS);
        assertEquals(4, spectrum.signedFrequencyAt(4), EPS); // even Nyquist stays positive
        assertListComplex(Arrays.asList(values), spectrum.inverse());
    }

    @Test
    public void radix2ThreeFiveAndSevenBranchesMatchIndependentDftAndInvert() {
        int[][] cases = {{2, 8}, {3, 9}, {5, 25}, {7, 49}};
        for (int[] testCase : cases) {
            int radix = testCase[0];
            int count = testCase[1];
            Sample sample = new Sample(0, 1, count);
            Waveform input = waveform(1.3, 0.7, -0.15, -0.4, 2.1, 0.22);
            Sampler sampler = new Sampler(sample);
            Spectrum actual = sampler.radixN(input, radix);
            Spectrum named = radix == 2 ? sampler.radix2(input) : radix == 3 ? sampler.radix3(input)
                    : radix == 5 ? sampler.radix5(input) : sampler.radix7(input);
            Complex[] values = input.sampleArray(sample);
            assertSpectrumMatches(dft(values), actual);
            assertSpectrumMatches(dft(values), named);
            assertListComplex(Arrays.asList(values), actual.inverse());
        }
    }

    @Test
    public void mixedAndPrimeLengthsAndBluesteinMatchIndependentDft() {
        for (int count : new int[]{6, 10, 12, 14, 15, 21, 11, 13, 17}) {
            Sample sample = new Sample(0, 1, count);
            Waveform input = waveform(0.9, 1.4, 0.1, -0.3, 2.4, -0.21);
            Sampler sampler = new Sampler(sample);
            Complex[] values = input.sampleArray(sample);
            Spectrum fast = sampler.fastFourierTransform(input);
            assertSpectrumMatches(dft(values), fast);
            assertSpectrumMatches(dft(values), sampler.blueStein(input));
            assertListComplex(Arrays.asList(values), fast.inverse());
        }
    }

    @Test
    public void radixPowerValidationAndUnconfiguredSamplerAreExplicit() {
        Sampler sampler = new Sampler(new Sample(0, 1, 6));
        assertThrows(IllegalArgumentException.class, () -> sampler.radix2(new Waveform()));
        assertThrows(IllegalArgumentException.class, () -> sampler.radixN(new Waveform(), 1));
        assertThrows(IllegalStateException.class, () -> new Sampler().fastFourierTransform(new Waveform()));
    }

    @Test
    public void spectraAndWaveformSamplesAreImmutableSnapshots() {
        Sample sample = new Sample(0, 1, 4);
        Waveform wave = new Waveform(Collections.singletonList(new Epicycle()));
        List<Complex> sampled = wave.sample(sample);
        Spectrum spectrum = new Sampler(sample).fft(wave);
        assertThrows(UnsupportedOperationException.class, () -> sampled.add(new Complex()));
        assertThrows(UnsupportedOperationException.class, () -> spectrum.getCoefficients().set(0, new Complex()));
        assertThrows(UnsupportedOperationException.class, () -> spectrum.inverse().clear());
        assertThrows(IndexOutOfBoundsException.class, () -> spectrum.get(-1));
    }

    @Test
    public void stftUsesPeriodicHannAndReportsFrameStartTimesWithoutPartialTail() {
        Sample sample = new Sample(10, 1, 8);
        Waveform input = waveform(0.8, 1.7, 0.15, -0.6, 2.2, -0.3);
        int frameLength = 4;
        int hop = 3;
        ShortTimeSpectrum stft = new Sampler(sample).shortTimeFourierTransform(
                input, frameLength, hop, Window.HANN);
        Complex[] values = input.sampleArray(sample);
        int expectedCount = 1 + (sample.getCount() - frameLength) / hop;
        assertEquals(expectedCount, stft.getFrameCount());
        for (int frame = 0; frame < expectedCount; frame++) {
            int offset = frame * hop;
            assertEquals(sample.timeAt(offset), stft.getFrameTime(frame), 0);
            Complex[] windowed = new Complex[frameLength];
            for (int n = 0; n < frameLength; n++) {
                double weight = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / frameLength);
                windowed[n] = values[offset + n].multiply(weight);
            }
            assertSpectrumMatches(dft(windowed), stft.getFrame(frame));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new Sampler(new Sample(0, 1, 3)).shortTimeFourierTransform(input, 4, 2, Window.HANN));
    }

    @Test
    public void haarRoundTripsFullAndPartialLevelsIncludingZeroPaddedInput() {
        for (int length : new int[]{1, 3, 5, 8}) {
            Sample sample = new Sample(0, 1, length);
            Waveform input = waveform(0.5, 1.2, 0.3, -0.2, 2.3, 0.8);
            int padded = 1;
            while (padded < length) padded <<= 1;
            int maxLevels = Integer.numberOfTrailingZeros(padded);
            for (int levels = length == 1 ? 0 : 1; levels <= maxLevels; levels++) {
                HaarSpectrum haar = new Sampler(sample).waveletTransform(input, levels);
                assertEquals(length, haar.getOriginalLength());
                assertEquals(padded, haar.getPaddedLength());
                assertEquals(levels, haar.getLevels());
                assertListComplex(haarReference(input.sample(sample), levels), haar.getCoefficients());
                assertListComplex(input.sample(sample), haar.inverse());
                assertThrows(UnsupportedOperationException.class,
                        () -> haar.getCoefficients().set(0, new Complex()));
            }
        }
    }

    @Test
    public void haarRejectsOutOfRangeLevelsAndZeroPadsWithComplexZeros() {
        Sample sample = new Sample(0, 1, 3);
        Waveform input = waveform(1, 0, 0, 0, 0, 0);
        HaarSpectrum full = new Sampler(sample).waveletTransform(input, 2);
        assertComplex(new Complex(1.5, 0), full.getCoefficients().get(0));
        assertComplex(new Complex(0.5, 0), full.getCoefficients().get(1));
        assertComplex(new Complex(), full.getCoefficients().get(2));
        assertComplex(new Complex(1 / Math.sqrt(2), 0), full.getCoefficients().get(3));
        assertListComplex(Arrays.asList(new Complex(1, 0), new Complex(1, 0), new Complex(1, 0)), full.inverse());
        assertThrows(IllegalArgumentException.class, () -> new Sampler(sample).waveletTransform(input, 3));
        assertThrows(IllegalArgumentException.class, () -> new Sampler(sample).waveletTransform(input, -1));
    }

    @Test
    public void stftAndHaarResultsAreImmutable() {
        Sample sample = new Sample(0, 1, 8);
        Waveform input = waveform(1, 1, 0, 0, 0, 0);
        ShortTimeSpectrum stft = new Sampler(sample).shortTimeFourierTransform(input, 4, 2, Window.RECTANGULAR);
        assertThrows(UnsupportedOperationException.class, () -> stft.getFrames().clear());
        assertThrows(UnsupportedOperationException.class, () -> stft.getFrameTimes().clear());
        assertThrows(IllegalArgumentException.class, () -> Window.HANN.coefficients(0));
    }

    @Test
    public void laplaceAndMellinUseIndependentLeftEndpointSums() {
        Sample sample = new Sample(0.5, 1, 4);
        Waveform input = waveform(0.6, 1.2, 0.2, -0.7, 2.3, 0.25);
        List<Complex> parameters = Arrays.asList(new Complex(0.4, 0.2), new Complex(1.1, -0.3));
        Sampler sampler = new Sampler(sample);
        List<Spectral> laplace = sampler.laplaceTransform(input, parameters);
        List<Spectral> laplaceAlias = sampler.laplacianTransform(input, parameters);
        assertEquals(parameters.size(), laplace.size());
        for (int i = 0; i < parameters.size(); i++) {
            assertComplex(parameters.get(i), laplace.get(i).getParameter());
            assertComplex(integralReference(input, sample, parameters.get(i), false), laplace.get(i).getCoefficient());
        }

        Sample positive = new Sample(1.25, 0.5, 4);
        Sampler positiveSampler = new Sampler(positive);
        List<Spectral> mellin = positiveSampler.mellinTransform(input, parameters);
        List<Spectral> alias = positiveSampler.meilinTransform(input, parameters);
        for (int i = 0; i < parameters.size(); i++) {
            Complex expected = integralReference(input, positive, parameters.get(i), true);
            assertComplex(expected, mellin.get(i).getCoefficient());
            assertComplex(expected, alias.get(i).getCoefficient());
        }
        assertThrows(UnsupportedOperationException.class, () -> laplace.clear());
        assertThrows(IllegalArgumentException.class,
                () -> new Sampler(new Sample(1, 4)).mellinTransform(input, parameters));
    }

    @Test
    public void invalidComplexSamplesAndTransformParametersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Spectrum(1, new Complex[]{new Complex(Double.NaN, 0)}));
        assertThrows(IllegalArgumentException.class,
                () -> new Spectrum(Double.POSITIVE_INFINITY, new Complex[]{new Complex()}));
        Sample sample = new Sample(2, 2);
        Sampler sampler = new Sampler(sample);
        Waveform input = new Waveform();
        assertThrows(IllegalArgumentException.class,
                () -> sampler.laplaceTransform(input, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> sampler.laplaceTransform(input, Arrays.asList(new Complex(Double.NaN, 0))));
        assertThrows(NullPointerException.class, () -> sampler.laplaceTransform(input, (List<Complex>) null));
        assertThrows(NullPointerException.class, () -> sampler.shortTimeFourierTransform(input, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> sampler.shortTimeFourierTransform(input, 0, 1, Window.HANN));
    }

    @Test
    public void complexDivisionAvoidsIntermediateOverflowAndUnderflow() {
        assertComplex(new Complex(1, 0), new Complex(1e308, 1e308).divide(new Complex(1e308, 1e308)));
        assertComplex(new Complex(1, 0), new Complex(0, Double.MAX_VALUE)
                .divide(new Complex(Double.MIN_NORMAL, Double.MAX_VALUE)));
        assertComplex(new Complex(1, 0), new Complex(Double.MIN_VALUE, Double.MIN_VALUE)
                .divide(new Complex(Double.MIN_VALUE, Double.MIN_VALUE)));
        assertEquals(1e308, new Complex(1e308, 1e308).divide(new Complex(1, 1)).getReal(), 1e293);
        assertComplex(new Complex(1.5, 2), new Complex(3, 4).divide(2));
        assertThrows(ArithmeticException.class, () -> new Complex(3, 4).divide(0));
    }

    @Test
    public void windowsExposeImmutableListsAndKnownPeriodicCoefficients() {
        assertEquals(Arrays.asList(1D, 1D, 1D, 1D), Window.RECTANGULAR.coefficients(4));
        assertEquals(0D, Window.HANN.coefficients(4).get(0), EPS);
        assertEquals(0.5D, Window.HANN.coefficients(4).get(1), EPS);
        assertEquals(1D, Window.HANN.coefficients(4).get(2), EPS);
        assertEquals(0.08D, Window.HAMMING.coefficients(4).get(0), EPS);
        assertEquals(1D, Window.HANN.coefficients(1).get(0), EPS);
        List<Complex> windowed = Window.HANN.apply(Arrays.asList(
                new Complex(2, 4), new Complex(2, 4), new Complex(2, 4), new Complex(2, 4)));
        assertComplex(new Complex(1, 2), windowed.get(1));
        assertThrows(UnsupportedOperationException.class, () -> windowed.clear());
        assertThrows(UnsupportedOperationException.class, () -> Window.HANN.coefficients(4).clear());
    }

    @Test
    public void singletonDefaultsAndEmptyWaveformValidateTimeArguments() {
        Waveform constant = waveform(2, 0, 0.3);
        Sampler sampler = new Sampler(new Sample(1, 1));
        assertComplex(constant.evaluate(0), sampler.fft(constant).get(0));
        assertListComplex(constant.sample(sampler.getSample()), sampler.waveletTransform(constant).inverse());
        assertComplex(constant.evaluate(0), sampler.shortTimeFourierTransform(constant).getFrame(0).get(0));
        assertThrows(IllegalArgumentException.class, () -> new Waveform().evaluate(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Waveform().integral(0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Sample(Double.MAX_VALUE, 2, 1e-308));
        assertThrows(IllegalArgumentException.class, () -> new Sample(1, Double.MAX_VALUE));
    }

    private static Waveform waveform(double... terms) {
        List<Epicycle> epicycles = new ArrayList<>();
        for (int i = 0; i < terms.length; i += 3) {
            epicycles.add(new Epicycle(terms[i], terms[i + 1], Angle.fromRadian(terms[i + 2])));
        }
        return new Waveform(epicycles);
    }

    private static Complex[] dft(Complex[] input) {
        Complex[] output = new Complex[input.length];
        for (int k = 0; k < input.length; k++) {
            double real = 0;
            double imaginary = 0;
            for (int n = 0; n < input.length; n++) {
                double phase = -2 * Math.PI * n * k / input.length;
                double c = Math.cos(phase);
                double s = Math.sin(phase);
                real += input[n].getReal() * c - input[n].getImaginary() * s;
                imaginary += input[n].getReal() * s + input[n].getImaginary() * c;
            }
            output[k] = new Complex(real, imaginary);
        }
        return output;
    }

    private static List<Complex> haarReference(List<Complex> input, int levels) {
        int padded = 1;
        while (padded < input.size()) padded <<= 1;
        Complex[] values = new Complex[padded];
        for (int i = 0; i < padded; i++) values[i] = i < input.size() ? input.get(i) : new Complex();
        int active = padded;
        for (int level = 0; level < levels; level++) {
            Complex[] next = values.clone();
            int half = active / 2;
            for (int i = 0; i < half; i++) {
                next[i] = values[2 * i].plus(values[2 * i + 1]).multiply(1 / Math.sqrt(2));
                next[half + i] = values[2 * i].subtract(values[2 * i + 1]).multiply(1 / Math.sqrt(2));
            }
            System.arraycopy(next, 0, values, 0, active);
            active = half;
        }
        return Arrays.asList(values);
    }

    private static Complex integralReference(Waveform waveform, Sample sample, Complex parameter, boolean mellin) {
        Complex sum = new Complex();
        double dt = 1 / sample.getRate();
        for (int i = 0; i < sample.getCount(); i++) {
            double time = sample.timeAt(i);
            Complex exponent = mellin
                    ? parameter.subtract(new Complex(1, 0)).multiply(Math.log(time))
                    : parameter.multiply(-time);
            double magnitude = Math.exp(exponent.getReal());
            Complex kernel = new Complex(magnitude * Math.cos(exponent.getImaginary()),
                    magnitude * Math.sin(exponent.getImaginary()));
            sum = sum.plus(waveform.evaluate(time).multiply(kernel).multiply(dt));
        }
        return sum;
    }

    private static double sumReal(Complex[] values) {
        double sum = 0;
        for (Complex value : values) sum += value.getReal();
        return sum;
    }

    private static double sumImaginary(Complex[] values) {
        double sum = 0;
        for (Complex value : values) sum += value.getImaginary();
        return sum;
    }

    private static void assertSpectrumMatches(Complex[] expected, Spectrum actual) {
        assertEquals(expected.length, actual.getCoefficients().size());
        for (int i = 0; i < expected.length; i++) assertComplex(expected[i], actual.get(i));
    }

    private static void assertListComplex(List<Complex> expected, List<Complex> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertComplex(expected.get(i), actual.get(i));
    }

    private static void assertComplex(Complex expected, Complex actual) {
        assertEquals(expected.getReal(), actual.getReal(), EPS);
        assertEquals(expected.getImaginary(), actual.getImaginary(), EPS);
    }
}
