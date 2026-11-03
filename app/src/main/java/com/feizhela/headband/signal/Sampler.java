package com.feizhela.headband.signal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Sampling and transform operations for a configured finite time interval. */
public final class Sampler {
    private final Sample sample;

    /** Kept for source compatibility; transforms require a Sample configuration. */
    public Sampler() { this.sample = null; }
    public Sampler(Sample sample) { this.sample = Objects.requireNonNull(sample, "sample"); }
    public Sample getSample() { return sample; }
    private Sample requireSample() {
        if (sample == null) throw new IllegalStateException("Sampler requires a Sample");
        return sample;
    }

    /** FIR 滤波器类型。 */
    public enum FilterType {
        /** 低通：保留截止频率以下的分量。 */
        LOW_PASS,
        /** 高通：保留截止频率以上的分量。 */
        HIGH_PASS
    }

    /**
     * 对实样本做线性相位 FIR 滤波。
     *
     * <p>滤波器由 Kaiser 窗 sinc 设计，核中心与输入对齐，边界补零，因此输出与输入
     * 同相、长度相同；这是离线分析用的零相位（非因果）滤波，不适合有严格时延要求的
     * 在线链路。
     *
     * @param samples 实样本
     * @param type    滤波类型
     * @param cutoff  截止频率，Hz；必须位于 {@code (0, rate/2)}
     * @param taps    抽头数；必须是不小于 3 的奇数
     * @return 滤波后的样本，长度与输入相同
     */
    public double[] filter(final double[] samples,
                           final FilterType type,
                           final double cutoff,
                           final int taps) {
        return filterSamples(samples, type, cutoff, taps, requireSample().getRate());
    }

    /**
     * 使用按截止频率自动估计的抽头数，其余参数同
     * {@link #filter(double[], FilterType, double, int)}。
     *
     * @param samples 实样本
     * @param type    滤波类型
     * @param cutoff  截止频率，Hz
     * @return 滤波后的样本
     */
    public double[] filter(final double[] samples, final FilterType type, final double cutoff) {
        final double rate = requireSample().getRate();
        return filterSamples(samples, type, cutoff, defaultFilterTaps(rate, cutoff), rate);
    }

    /**
     * 先把 {@link Waveform} 按当前 {@link Sample} 采样为实部，再做
     * {@link #filter(double[], FilterType, double, int)}。
     *
     * @param waveform 波形
     * @param type     滤波类型
     * @param cutoff   截止频率，Hz
     * @param taps     抽头数
     * @return 滤波后的实样本
     */
    public double[] filter(final Waveform waveform,
                           final FilterType type,
                           final double cutoff,
                           final int taps) {
        return filterSamples(realSamples(waveform), type, cutoff, taps,
                requireSample().getRate());
    }

    /**
     * 同 {@link #filter(Waveform, FilterType, double, int)}，抽头数自动估计。
     *
     * @param waveform 波形
     * @param type     滤波类型
     * @param cutoff   截止频率，Hz
     * @return 滤波后的实样本
     */
    public double[] filter(final Waveform waveform, final FilterType type, final double cutoff) {
        final double rate = requireSample().getRate();
        return filterSamples(realSamples(waveform), type, cutoff,
                defaultFilterTaps(rate, cutoff), rate);
    }

    /**
     * 先低通抗混叠，再重采样到目标采样率。
     *
     * <p>低通截止取 {@code 0.4 * min(源采样率, 目标采样率)}，然后用 Kaiser 窗 sinc
     * 滤波器滤波，最后在目标时刻用 8 点 Lanczos 插值重采样。输出样本从源起点开始，
     * 输出长度为 {@code round(样本数 * 目标采样率 / 源采样率)}。目标采样率等于源
     * 采样率时直接返回输入副本。
     *
     * @param samples    源实样本
     * @param targetRate 目标采样率，Hz
     * @return 重采样后的实样本
     */
    public double[] resample(final double[] samples, final double targetRate) {
        final double sourceRate = requireSample().getRate();
        return resampleSamples(samples, sourceRate, targetRate,
                defaultResampleTaps(sourceRate, targetRate));
    }

    /**
     * 同 {@link #resample(double[], double)}，但显式指定抗混叠滤波器的抽头数。
     *
     * @param samples    源实样本
     * @param targetRate 目标采样率，Hz
     * @param taps       抗混叠滤波器的抽头数
     * @return 重采样后的实样本
     */
    public double[] resample(final double[] samples, final double targetRate, final int taps) {
        return resampleSamples(samples, requireSample().getRate(), targetRate, taps);
    }

    /**
     * 同 {@link #resample(double[], double)}，但显式指定抗混叠截止频率和抽头数。
     *
     * @param samples    源实样本
     * @param targetRate 目标采样率，Hz
     * @param cutoff     抗混叠截止频率，Hz；必须小于
     *                   {@code min(源采样率, 目标采样率) / 2}
     * @param taps       抗混叠滤波器的抽头数
     * @return 重采样后的实样本
     */
    public double[] resample(final double[] samples,
                             final double targetRate,
                             final double cutoff,
                             final int taps) {
        return resampleSamples(samples, requireSample().getRate(), targetRate, cutoff, taps);
    }

    /**
     * 先把 {@link Waveform} 采样为实部，再重采样到目标采样率。
     *
     * @param waveform   波形
     * @param targetRate 目标采样率，Hz
     * @return 重采样后的实样本
     */
    public double[] resample(final Waveform waveform, final double targetRate) {
        final double sourceRate = requireSample().getRate();
        return resampleSamples(realSamples(waveform), sourceRate, targetRate,
                defaultResampleTaps(sourceRate, targetRate));
    }

    /**
     * 同 {@link #resample(Waveform, double)}，但显式指定抗混叠滤波器的抽头数。
     *
     * @param waveform   波形
     * @param targetRate 目标采样率，Hz
     * @param taps       抗混叠滤波器的抽头数
     * @return 重采样后的实样本
     */
    public double[] resample(final Waveform waveform, final double targetRate, final int taps) {
        return resampleSamples(realSamples(waveform), requireSample().getRate(), targetRate, taps);
    }

    /**
     * 同 {@link #resample(Waveform, double)}，但显式指定抗混叠截止频率和抽头数。
     *
     * @param waveform   波形
     * @param targetRate 目标采样率，Hz
     * @param cutoff     抗混叠截止频率，Hz
     * @param taps       抗混叠滤波器的抽头数
     * @return 重采样后的实样本
     */
    public double[] resample(final Waveform waveform,
                             final double targetRate,
                             final double cutoff,
                             final int taps) {
        return resampleSamples(realSamples(waveform), requireSample().getRate(),
                targetRate, cutoff, taps);
    }

    public Spectrum radix2(Waveform waveform) { return radixTransform(waveform, 2); }
    public Spectrum radix3(Waveform waveform) { return radixTransform(waveform, 3); }
    public Spectrum radix5(Waveform waveform) { return radixTransform(waveform, 5); }
    public Spectrum radix7(Waveform waveform) { return radixTransform(waveform, 7); }
    public Spectrum radixN(Waveform waveform) { return fastFourierTransform(waveform); }
    public Spectrum radixN(Waveform waveform, int radix) { return radixTransform(waveform, radix); }
    public Spectrum blueStein(Waveform waveform) {
        Sample config = requireSample();
        return new Spectrum(config.getRate(), bluestein(requireWaveform(waveform).sampleArray(config)));
    }
    public Spectrum fastFourierTransform(Waveform waveform) {
        Sample config = requireSample();
        return new Spectrum(config.getRate(), fft(requireWaveform(waveform).sampleArray(config)));
    }
    public Spectrum discreteFourierTransform(Waveform waveform) {
        Sample config = requireSample();
        return new Spectrum(config.getRate(), dft(requireWaveform(waveform).sampleArray(config), false));
    }
    /** Laplace transform at caller-specified complex parameters. */
    public List<Spectral> laplaceTransform(Waveform waveform, List<Complex> parameters) {
        return integralTransform(waveform, SignalMath.array(parameters), false);
    }
    public List<Spectral> laplaceTransform(Waveform waveform, Complex[] parameters) {
        return integralTransform(waveform, parameters, false);
    }
    public List<Spectral> mellinTransform(Waveform waveform, List<Complex> parameters) {
        return integralTransform(waveform, SignalMath.array(parameters), true);
    }
    public List<Spectral> mellinTransform(Waveform waveform, Complex[] parameters) {
        return integralTransform(waveform, parameters, true);
    }
    public List<Spectral> meilinTransform(Waveform waveform, List<Complex> parameters) {
        return mellinTransform(waveform, parameters);
    }
    public List<Spectral> meilinTransform(Waveform waveform, Complex[] parameters) {
        return mellinTransform(waveform, parameters);
    }
    public List<Spectral> laplacianTransform(Waveform waveform, List<Complex> parameters) {
        return laplaceTransform(waveform, parameters);
    }
    public List<Spectral> laplacianTransform(Waveform waveform, Complex[] parameters) {
        return laplaceTransform(waveform, parameters);
    }
    public Spectrum dft(Waveform waveform) { return discreteFourierTransform(waveform); }
    public Spectrum fft(Waveform waveform) { return fastFourierTransform(waveform); }

    public ShortTimeSpectrum shortTimeFourierTransform(Waveform waveform, int frameLength, int hop, Window window) {
        if (frameLength < 1 || hop < 1) throw new IllegalArgumentException("frameLength and hop must be positive");
        Objects.requireNonNull(window, "window");
        Sample config = requireSample();
        Complex[] input = requireWaveform(waveform).sampleArray(config);
        if (input.length < frameLength) throw new IllegalArgumentException("frame exceeds samples");
        int count = 1 + (input.length - frameLength) / hop;
        double[] times = new double[count];
        Spectrum[] frames = new Spectrum[count];
        for (int frame = 0; frame < count; frame++) {
            int offset = frame * hop;
            Complex[] values = Arrays.copyOfRange(input, offset, offset + frameLength);
            frames[frame] = new Spectrum(config.getRate(), fft(window.apply(values)));
            times[frame] = config.getStartTime() + offset / config.getRate();
        }
        return new ShortTimeSpectrum(times, frames);
    }
    public ShortTimeSpectrum shortTimeFourierTransform(Waveform waveform, int frameLength, int hop) {
        return shortTimeFourierTransform(waveform, frameLength, hop, Window.HANN);
    }

    /** A single full-length Hann frame; use the configured overload for multiple frames. */
    public ShortTimeSpectrum shortTimeFourierTransform(Waveform waveform) {
        Sample config = requireSample();
        int frameLength = config.getCount();
        return shortTimeFourierTransform(waveform, frameLength, Math.max(1, frameLength / 2), Window.HANN);
    }

    public HaarSpectrum waveletTransform(Waveform waveform, int levels) {
        Complex[] input = requireWaveform(waveform).sampleArray(requireSample());
        int padded = SignalMath.nextPowerOfTwo(input.length);
        int maxLevels = Integer.numberOfTrailingZeros(padded);
        if (levels < 0 || levels > maxLevels) throw new IllegalArgumentException("invalid Haar levels");
        Complex[] values = Arrays.copyOf(input, padded);
        Arrays.fill(values, input.length, padded, new Complex());
        int active = padded;
        for (int level = 0; level < levels; level++) {
            Complex[] next = values.clone();
            int half = active / 2;
            for (int i = 0; i < half; i++) {
                next[i] = values[2 * i].plus(values[2 * i + 1]).multiply(1 / Math.sqrt(2D));
                next[half + i] = values[2 * i].subtract(values[2 * i + 1]).multiply(1 / Math.sqrt(2D));
            }
            System.arraycopy(next, 0, values, 0, active);
            active = half;
        }
        return new HaarSpectrum(values, input.length, levels);
    }
    public HaarSpectrum waveletTransform(Waveform waveform) {
        int padded = SignalMath.nextPowerOfTwo(requireSample().getCount());
        return waveletTransform(waveform, Integer.numberOfTrailingZeros(padded));
    }
    public HaarSpectrum haarTransform(Waveform waveform, int levels) { return waveletTransform(waveform, levels); }

    private double[] realSamples(final Waveform waveform) {
        final Complex[] values = requireWaveform(waveform).sampleArray(requireSample());
        final double[] samples = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            samples[index] = values[index].getReal();
        }
        return samples;
    }

    private static double[] filterSamples(final double[] samples,
                                          final FilterType type,
                                          final double cutoff,
                                          final int taps,
                                          final double rate) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(type, "type");
        if (samples.length == 0) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        for (double value : samples) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("samples must be finite");
            }
        }
        if (!Double.isFinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("sampling rate must be positive and finite");
        }
        if (!Double.isFinite(cutoff) || cutoff <= 0.0 || cutoff >= rate * 0.5) {
            throw new IllegalArgumentException("cutoff must be in (0, rate/2)");
        }
        if (taps < 3 || (taps & 1) == 0) {
            throw new IllegalArgumentException("taps must be an odd number of at least 3");
        }
        return applyFir(samples, firCoefficients(type, cutoff, rate, taps));
    }

    private static double[] applyFir(final double[] samples, final double[] coefficients) {
        final int length = samples.length;
        final int taps = coefficients.length;
        final int middle = taps / 2;
        final double[] output = new double[length];
        for (int index = 0; index < length; index++) {
            double sum = 0.0;
            for (int tap = 0; tap < taps; tap++) {
                final int source = index + middle - tap;
                if (source >= 0 && source < length) {
                    sum += coefficients[tap] * samples[source];
                }
            }
            output[index] = sum;
        }
        return output;
    }

    private static double[] firCoefficients(final FilterType type,
                                            final double cutoff,
                                            final double rate,
                                            final int taps) {
        final int middle = (taps - 1) / 2;
        final double normalized = cutoff / rate;
        final double[] window = kaiserWindow(taps, 8.0);
        final double[] coefficients = new double[taps];
        for (int tap = 0; tap < taps; tap++) {
            final int offset = tap - middle;
            final double argument = 2.0 * Math.PI * normalized * offset;
            final double sinc = offset == 0 ? 1.0 : Math.sin(argument) / argument;
            coefficients[tap] = 2.0 * normalized * sinc * window[tap];
        }
        if (type == FilterType.HIGH_PASS) {
            for (int tap = 0; tap < taps; tap++) {
                coefficients[tap] = -coefficients[tap];
            }
            coefficients[middle] += 1.0;
        }
        double gain = 0.0;
        for (int tap = 0; tap < taps; tap++) {
            gain += type == FilterType.LOW_PASS
                    ? coefficients[tap]
                    : (tap % 2 == 0 ? coefficients[tap] : -coefficients[tap]);
        }
        if (Math.abs(gain) > 1e-12) {
            for (int tap = 0; tap < taps; tap++) {
                coefficients[tap] /= gain;
            }
        }
        return coefficients;
    }

    private static double[] kaiserWindow(final int length, final double beta) {
        final double[] window = new double[length];
        final double middle = (length - 1) / 2.0;
        final double denominator = besselI0(beta);
        for (int index = 0; index < length; index++) {
            final double ratio = middle == 0.0 ? 0.0 : (index - middle) / middle;
            window[index] = besselI0(beta * Math.sqrt(Math.max(0.0, 1.0 - ratio * ratio)))
                    / denominator;
        }
        return window;
    }

    private static double besselI0(final double value) {
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

    private static int defaultFilterTaps(final double rate, final double cutoff) {
        final double edge = Math.min(cutoff, rate * 0.5 - cutoff);
        final double transition = Math.max(edge * 0.1, rate * 1e-6);
        return kaiserTaps(rate, transition);
    }

    private static int defaultResampleTaps(final double sourceRate, final double targetRate) {
        final double minimum = Math.min(sourceRate, targetRate);
        final double transition = Math.max(minimum * 0.1, sourceRate * 1e-6);
        return kaiserTaps(sourceRate, transition);
    }

    private static int kaiserTaps(final double rate, final double transition) {
        final double omega = 2.0 * Math.PI * transition / rate;
        int taps = (int) Math.ceil((80.0 - 8.0) / (2.285 * omega));
        taps = Math.max(31, Math.min(2047, taps));
        if ((taps & 1) == 0) {
            taps++;
        }
        return taps;
    }

    private static double[] resampleSamples(final double[] samples,
                                            final double sourceRate,
                                            final double targetRate,
                                            final int taps) {
        return resampleSamples(samples, sourceRate, targetRate,
                0.4 * Math.min(sourceRate, targetRate), taps);
    }

    private static double[] resampleSamples(final double[] samples,
                                            final double sourceRate,
                                            final double targetRate,
                                            final double cutoff,
                                            final int taps) {
        Objects.requireNonNull(samples, "samples");
        if (samples.length == 0) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        for (double value : samples) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("samples must be finite");
            }
        }
        if (!Double.isFinite(sourceRate) || sourceRate <= 0.0) {
            throw new IllegalArgumentException("source rate must be positive and finite");
        }
        if (!Double.isFinite(targetRate) || targetRate <= 0.0) {
            throw new IllegalArgumentException("target rate must be positive and finite");
        }
        if (!Double.isFinite(cutoff) || cutoff <= 0.0
                || cutoff >= 0.5 * Math.min(sourceRate, targetRate)) {
            throw new IllegalArgumentException(
                    "cutoff must be in (0, min(sourceRate, targetRate)/2)");
        }
        if (taps < 3 || (taps & 1) == 0) {
            throw new IllegalArgumentException("taps must be an odd number of at least 3");
        }
        if (Math.abs(targetRate - sourceRate) <= 1e-12) {
            return samples.clone();
        }
        final double[] filtered = filterSamples(
                samples, FilterType.LOW_PASS, cutoff, taps, sourceRate);
        final long count = Math.round((double) samples.length * targetRate / sourceRate);
        if (count < 1 || count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("resampled length is out of range");
        }
        final int outputCount = (int) count;
        final double ratio = sourceRate / targetRate;
        final double[] output = new double[outputCount];
        for (int index = 0; index < outputCount; index++) {
            output[index] = lanczos(filtered, index * ratio, 4);
        }
        return output;
    }

    /** 8 点 Lanczos 插值；边界按可用样本归一化。 */
    private static double lanczos(final double[] samples,
                                  final double position,
                                  final int halfWidth) {
        final int center = (int) Math.floor(position);
        double sum = 0.0;
        double weightSum = 0.0;
        for (int offset = -halfWidth + 1; offset <= halfWidth; offset++) {
            final int index = center + offset;
            if (index < 0 || index >= samples.length) {
                continue;
            }
            final double distance = position - index;
            final double weight = sinc(distance) * sinc(distance / halfWidth);
            sum += samples[index] * weight;
            weightSum += weight;
        }
        if (weightSum == 0.0) {
            return samples[Math.min(Math.max(center, 0), samples.length - 1)];
        }
        return sum / weightSum;
    }

    private static double sinc(final double value) {
        if (Math.abs(value) < 1e-12) {
            return 1.0;
        }
        final double angle = Math.PI * value;
        return Math.sin(angle) / angle;
    }

    private Spectrum radixTransform(Waveform waveform, int radix) {
        if (radix < 2) throw new IllegalArgumentException("radix must be at least 2");
        Sample config = requireSample();
        Complex[] input = requireWaveform(waveform).sampleArray(config);
        if (!SignalMath.isPower(input.length, radix)) throw new IllegalArgumentException("length must be a power of radix");
        return new Spectrum(config.getRate(), mixedRadix(input, radix));
    }
    private static Waveform requireWaveform(Waveform waveform) { return Objects.requireNonNull(waveform, "waveform"); }

    public static List<Complex> inverseFourier(List<Complex> values) {
        return SignalMath.list(inverseFourier(SignalMath.array(values)));
    }
    public static Complex[] inverseFourier(Complex[] values) {
        SignalMath.validate(values);
        Complex[] conjugate = new Complex[values.length];
        for (int i = 0; i < values.length; i++) conjugate[i] = values[i].conjugate();
        Complex[] transformed = fft(conjugate);
        for (int i = 0; i < transformed.length; i++) transformed[i] = transformed[i].conjugate().divide(transformed.length);
        return transformed;
    }

    private static Complex[] fft(Complex[] values) {
        SignalMath.validate(values);
        if (values.length == 0) throw new IllegalArgumentException("empty transform");
        if (values.length == 1) return SignalMath.copy(values);
        int factor = smallestFactor(values.length);
        if (factor == values.length) return bluestein(values);
        return mixedCooleyTukey(values, factor);
    }

    private static int smallestFactor(int n) {
        if ((n & 1) == 0) return 2;
        for (int factor = 3; (long) factor * factor <= n; factor += 2) {
            if (n % factor == 0) return factor;
        }
        return n;
    }

    /** Cooley-Tukey recursion using any factor, including mixed composite lengths. */
    private static Complex[] mixedCooleyTukey(Complex[] input, int radix) {
        int n = input.length;
        if (n == 1) return SignalMath.copy(input);
        int subLength = n / radix;
        Complex[][] subTransforms = new Complex[radix][];
        for (int residue = 0; residue < radix; residue++) {
            Complex[] subsequence = new Complex[subLength];
            for (int j = 0; j < subLength; j++) subsequence[j] = input[radix * j + residue];
            int nextFactor = smallestFactor(subLength);
            subTransforms[residue] = nextFactor == subLength && subLength > 1
                    ? bluestein(subsequence) : mixedCooleyTukey(subsequence, nextFactor);
        }
        Complex[] output = new Complex[n];
        for (int k = 0; k < n; k++) {
            Complex sum = new Complex();
            for (int residue = 0; residue < radix; residue++) {
                sum = sum.plus(subTransforms[residue][k % subLength]
                        .multiply(SignalMath.exp(-2 * Math.PI * residue * k / n)));
            }
            output[k] = sum;
        }
        return output;
    }

    /** Cooley-Tukey decimation-in-time for lengths that are powers of radix. */
    private static Complex[] mixedRadix(Complex[] input, int radix) {
        int n = input.length;
        if (!SignalMath.isPower(n, radix)) throw new IllegalArgumentException("length must be a radix power");
        if (n == 1) return SignalMath.copy(input);
        int subLength = n / radix;
        Complex[][] subTransforms = new Complex[radix][];
        for (int residue = 0; residue < radix; residue++) {
            Complex[] subsequence = new Complex[subLength];
            for (int j = 0; j < subLength; j++) subsequence[j] = input[radix * j + residue];
            subTransforms[residue] = mixedRadix(subsequence, radix);
        }
        Complex[] output = new Complex[n];
        for (int k = 0; k < n; k++) {
            Complex sum = new Complex();
            for (int residue = 0; residue < radix; residue++) {
                double angle = -2 * Math.PI * residue * k / n;
                sum = sum.plus(subTransforms[residue][k % subLength].multiply(SignalMath.exp(angle)));
            }
            output[k] = sum;
        }
        return output;
    }

    private static Complex[] dft(Complex[] input, boolean inverse) {
        SignalMath.validate(input);
        int n = input.length;
        if (n == 0) throw new IllegalArgumentException("empty transform");
        Complex[] result = new Complex[n];
        double sign = inverse ? 1D : -1D;
        for (int k = 0; k < n; k++) {
            double real = 0D, imaginary = 0D;
            for (int j = 0; j < n; j++) {
                double angle = sign * 2 * Math.PI * j * k / n;
                double cos = Math.cos(angle), sin = Math.sin(angle);
                real += input[j].getReal() * cos - input[j].getImaginary() * sin;
                imaginary += input[j].getReal() * sin + input[j].getImaginary() * cos;
            }
            result[k] = inverse ? new Complex(real / n, imaginary / n) : new Complex(real, imaginary);
        }
        return result;
    }

    /** Bluestein chirp-z reduction to a power-of-two convolution. */
    private static Complex[] bluestein(Complex[] input) {
        int n = input.length;
        if (n == 0) throw new IllegalArgumentException("empty transform");
        if (n == 1) return SignalMath.copy(input);
        long needed = 2L * n - 1;
        if (needed > (1L << 30)) throw new IllegalArgumentException("transform is too large");
        int convolutionLength = SignalMath.nextPowerOfTwo((int) needed);
        Complex[] a = SignalMath.zeros(convolutionLength);
        Complex[] b = SignalMath.zeros(convolutionLength);
        for (int i = 0; i < n; i++) {
            double phase = Math.PI * ((long) i * i % (2L * n)) / n;
            Complex negativeChirp = SignalMath.exp(-phase);
            Complex positiveChirp = SignalMath.exp(phase);
            a[i] = input[i].multiply(negativeChirp);
            b[i] = positiveChirp;
            if (i != 0) b[convolutionLength - i] = positiveChirp;
        }
        Complex[] fa = radixTwo(a);
        Complex[] fb = radixTwo(b);
        for (int i = 0; i < convolutionLength; i++) fa[i] = fa[i].multiply(fb[i]);
        Complex[] convolution = inverseRadixTwo(fa);
        Complex[] output = new Complex[n];
        for (int k = 0; k < n; k++) {
            double phase = Math.PI * ((long) k * k % (2L * n)) / n;
            output[k] = convolution[k].multiply(SignalMath.exp(-phase));
        }
        return output;
    }

    private static Complex[] radixTwo(Complex[] input) {
        int n = input.length;
        if (n == 0 || (n & (n - 1)) != 0) throw new IllegalArgumentException("radix-2 length required");
        Complex[] values = SignalMath.copy(input);
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >>> 1;
            for (; (j & bit) != 0; bit >>>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { Complex temp = values[i]; values[i] = values[j]; values[j] = temp; }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angleStep = -2 * Math.PI / length;
            for (int start = 0; start < n; start += length) {
                for (int j = 0; j < length / 2; j++) {
                    Complex even = values[start + j];
                    Complex odd = values[start + j + length / 2].multiply(SignalMath.exp(angleStep * j));
                    values[start + j] = even.plus(odd);
                    values[start + j + length / 2] = even.subtract(odd);
                }
            }
            if (length == n) break;
        }
        return values;
    }
    private static Complex[] inverseRadixTwo(Complex[] input) {
        Complex[] result = new Complex[input.length];
        for (int i = 0; i < input.length; i++) result[i] = input[i].conjugate();
        result = radixTwo(result);
        for (int i = 0; i < result.length; i++) result[i] = result[i].conjugate().divide(result.length);
        return result;
    }

    private List<Spectral> integralTransform(Waveform waveform, Complex[] parameters, boolean mellin) {
        if (parameters == null || parameters.length == 0) throw new IllegalArgumentException("parameters cannot be empty");
        for (Complex parameter : parameters) SignalMath.finite(parameter, "parameter");
        Sample config = requireSample();
        if (mellin && config.getStartTime() <= 0D) throw new IllegalArgumentException("Mellin requires positive startTime");
        Complex[] input = requireWaveform(waveform).sampleArray(config);
        double dt = 1D / config.getRate();
        List<Spectral> result = new ArrayList<>(parameters.length);
        for (Complex parameter : parameters) {
            Complex sum = new Complex();
            for (int n = 0; n < input.length; n++) {
                double time = config.timeAt(n);
                Complex exponent = mellin
                        ? parameter.subtract(new Complex(1D, 0D)).multiply(Math.log(time))
                        : parameter.multiply(-time);
                sum = sum.plus(input[n].multiply(expComplex(exponent)).multiply(dt));
            }
            result.add(new Spectral(parameter, sum));
        }
        return Collections.unmodifiableList(result);
    }
    private static Complex expComplex(Complex value) {
        double magnitude = Math.exp(value.getReal());
        return new Complex(magnitude * Math.cos(value.getImaginary()), magnitude * Math.sin(value.getImaginary()));
    }

    /**
     * Array FFT using radix 2, mixed 2/3/5/7, or Bluestein dispatch.
     * Forward is unnormalized; inverse divides by N. Input is never modified.
     * Empty arrays are accepted. Like the legacy analyser, nonfinite components propagate.
     */
    public static Complex[] transformArray(Complex[] input, boolean inverse) {
        Complex[] data = copyTransformArray(input);
        if (data.length <= 1) return data;
        if (arrayIsPowerOf(data.length, 2)) return arrayRadix2Impl(data, inverse);
        if (arrayIsSmooth(data.length)) {
            arrayMixedRadixImpl(data, inverse, 0);
            return data;
        }
        return arrayBlueSteinImpl(data, inverse);
    }

    public static Complex[] forwardArray(Complex[] input) { return transformArray(input, false); }
    public static Complex[] inverseArray(Complex[] input) { return transformArray(input, true); }

    /** Direct DFT, with the same normalization and ownership rules as transformArray. */
    public static Complex[] discreteTransformArray(Complex[] input, boolean inverse) {
        Complex[] data = copyTransformArray(input);
        arrayDirectTransform(data, inverse);
        return data;
    }

    /** Radix 2 accepts empty input; radix 3/5/7 require a positive power of the radix. */
    public static Complex[] radixTransformArray(Complex[] input, int radix, boolean inverse) {
        Complex[] data = copyTransformArray(input);
        if (radix == 2) return arrayRadix2Impl(data, inverse);
        if (radix != 3 && radix != 5 && radix != 7) {
            throw new IllegalArgumentException("radix must be 2, 3, 5, or 7");
        }
        if (!arrayIsPowerOf(data.length, radix)) {
            throw new IllegalArgumentException("radix" + radix + " requires length to be a power of " + radix);
        }
        arrayMixedRadixImpl(data, inverse, radix);
        return data;
    }

    public static Complex[] mixedTransformArray(Complex[] input, boolean inverse) {
        Complex[] data = copyTransformArray(input);
        arrayMixedRadixImpl(data, inverse, 0);
        return data;
    }

    public static Complex[] bluesteinTransformArray(Complex[] input, boolean inverse) {
        return arrayBlueSteinImpl(copyTransformArray(input), inverse);
    }

    private static Complex[] copyTransformArray(Complex[] input) {
        Objects.requireNonNull(input, "input");
        Complex[] data = input.clone();
        for (int i = 0; i < data.length; i++) Objects.requireNonNull(data[i], "input[" + i + "]");
        return data;
    }

    private static boolean arrayIsPowerOf(final int value, final int base) {
        if (value == 0) {
            return false;
        }
        int remaining = value;
        while (remaining % base == 0) {
            remaining /= base;
        }
        return remaining == 1;
    }

    private static boolean arrayIsSmooth(final int value) {
        if (value == 0) {
            return false;
        }
        int remaining = value;
        while (remaining % 2 == 0) {
            remaining /= 2;
        }
        while (remaining % 3 == 0) {
            remaining /= 3;
        }
        while (remaining % 5 == 0) {
            remaining /= 5;
        }
        while (remaining % 7 == 0) {
            remaining /= 7;
        }
        return remaining == 1;
    }

    private static int arraySmallestRadixFactor(final int value) {
        if (value % 2 == 0) {
            return 2;
        }
        if (value % 3 == 0) {
            return 3;
        }
        if (value % 5 == 0) {
            return 5;
        }
        if (value % 7 == 0) {
            return 7;
        }
        return 1;
    }

    private static Complex[] arrayRadix2Impl(final Complex[] input, final boolean inverse) {
        final int length = input.length;
        if (length <= 1) {
            return input.clone();
        }
        if (!arrayIsPowerOf(length, 2)) {
            throw new IllegalArgumentException("radix2 requires length to be a power of 2");
        }
        final Complex[] data = input.clone();
        for (int firstIndex = 1, secondIndex = 0; firstIndex < length; firstIndex++) {
            int bit = length >> 1;
            for (; (secondIndex & bit) != 0; bit >>= 1) {
                secondIndex ^= bit;
            }
            secondIndex ^= bit;
            if (firstIndex < secondIndex) {
                final Complex swap = data[firstIndex];
                data[firstIndex] = data[secondIndex];
                data[secondIndex] = swap;
            }
        }
        int stage = 2;
        while (stage <= length) {
            final double angle = 2.0 * Math.PI / stage * (inverse ? 1.0 : -1.0);
            final Complex twiddleStep = new Complex(Math.cos(angle), Math.sin(angle));
            for (int block = 0; block < length; block += stage) {
                Complex twiddle = new Complex(1.0, 0.0);
                for (int offset = 0; offset < stage / 2; offset++) {
                    final Complex even = data[block + offset];
                    final Complex odd = data[block + offset + stage / 2].multiply(twiddle);
                    data[block + offset] = even.plus(odd);
                    data[block + offset + stage / 2] = even.subtract(odd);
                    twiddle = twiddle.multiply(twiddleStep);
                }
            }
            if (stage == length) {
                break;
            }
            stage <<= 1;
        }
        if (inverse) {
            final double scale = 1.0 / length;
            for (int index = 0; index < length; index++) {
                data[index] = data[index].multiply(scale);
            }
        }
        return data;
    }

    private static Complex[] arrayBlueSteinImpl(final Complex[] input, final boolean inverse) {
        final int length = input.length;
        if (length <= 1) {
            return input.clone();
        }
        if (arrayIsSmooth(length)) {
            final Complex[] data = input.clone();
            arrayMixedRadixImpl(data, inverse, 0);
            return data;
        }
        int expandedLength = 1;
        final long required = 2L * length - 1L;
        while (expandedLength < required) {
            if (expandedLength > (1 << 29)) {
                throw new IllegalArgumentException("signal length is too large for Bluestein");
            }
            expandedLength <<= 1;
        }
        final Complex[] first = new Complex[expandedLength];
        final Complex[] second = new Complex[expandedLength];
        Arrays.fill(first, new Complex());
        Arrays.fill(second, new Complex());
        final double sign = inverse ? 1.0 : -1.0;
        final long doubledModulus = 2L * length;
        for (int index = 0; index < length; index++) {
            final long squareIndex = ((long) index * index) % doubledModulus;
            final double angle = sign * Math.PI * squareIndex / length;
            final Complex rotation = new Complex(Math.cos(angle), Math.sin(angle));
            first[index] = input[index].multiply(rotation);
            second[index] = rotation.conjugate();
            if (index != 0) {
                second[expandedLength - index] = rotation.conjugate();
            }
        }
        Complex[] firstTransform = arrayRadix2Impl(first, false);
        Complex[] secondTransform = arrayRadix2Impl(second, false);
        for (int index = 0; index < expandedLength; index++) {
            firstTransform[index] = firstTransform[index].multiply(secondTransform[index]);
        }
        firstTransform = arrayRadix2Impl(firstTransform, true);
        final Complex[] output = new Complex[length];
        final double normalization = inverse ? 1.0 / length : 1.0;
        for (int index = 0; index < length; index++) {
            final long squareIndex = ((long) index * index) % doubledModulus;
            final double angle = sign * Math.PI * squareIndex / length;
            final Complex rotation = new Complex(Math.cos(angle), Math.sin(angle));
            output[index] = firstTransform[index].multiply(rotation).multiply(normalization);
        }
        return output;
    }

    private static void arrayDirectTransform(final Complex[] data, final boolean inverse) {
        final int length = data.length;
        if (length <= 1) {
            return;
        }
        final Complex[] output = new Complex[length];
        final double sign = inverse ? 1.0 : -1.0;
        for (int frequencyIndex = 0; frequencyIndex < length; frequencyIndex++) {
            double real = 0.0;
            double imaginary = 0.0;
            for (int position = 0; position < length; position++) {
                final double angle = sign * 2.0 * Math.PI
                        * (double) frequencyIndex * (double) position / (double) length;
                final double cosine = Math.cos(angle);
                final double sine = Math.sin(angle);
                final double a = data[position].getReal();
                final double b = data[position].getImaginary();
                real += a * cosine - b * sine;
                imaginary += a * sine + b * cosine;
            }
            output[frequencyIndex] = inverse
                    ? new Complex(real / length, imaginary / length)
                    : new Complex(real, imaginary);
        }
        System.arraycopy(output, 0, data, 0, length);
    }

    private static void arrayMixedRadixImpl(final Complex[] data,
                                       final boolean inverse,
                                       final int forcedRadix) {
        final int length = data.length;
        if (length <= 1) {
            return;
        }
        if (length <= 7) {
            arrayDirectTransform(data, inverse);
            return;
        }
        int radix = forcedRadix;
        if (radix == 0) {
            radix = arraySmallestRadixFactor(length);
        }
        if (radix <= 1 || length % radix != 0) {
            arrayDirectTransform(data, inverse);
            return;
        }
        final int subLength = length / radix;
        final Complex[][] subSequences = new Complex[radix][subLength];
        for (int remainderIndex = 0; remainderIndex < radix; remainderIndex++) {
            for (int position = 0; position < subLength; position++) {
                subSequences[remainderIndex][position] = data[remainderIndex + position * radix];
            }
            arrayMixedRadixImpl(subSequences[remainderIndex], inverse, forcedRadix);
        }
        final double sign = inverse ? 1.0 : -1.0;
        for (int remainderIndex = 0; remainderIndex < radix; remainderIndex++) {
            for (int frequencyIndex = 0; frequencyIndex < subLength; frequencyIndex++) {
                final double angle = sign * 2.0 * Math.PI
                        * (double) remainderIndex * (double) frequencyIndex / (double) length;
                final Complex twiddle = new Complex(Math.cos(angle), Math.sin(angle));
                subSequences[remainderIndex][frequencyIndex] =
                        subSequences[remainderIndex][frequencyIndex].multiply(twiddle);
            }
        }
        for (int frequencyIndex = 0; frequencyIndex < subLength; frequencyIndex++) {
            final Complex[] group = new Complex[radix];
            for (int remainderIndex = 0; remainderIndex < radix; remainderIndex++) {
                group[remainderIndex] = subSequences[remainderIndex][frequencyIndex];
            }
            arrayDirectTransform(group, inverse);
            for (int outputIndex = 0; outputIndex < radix; outputIndex++) {
                data[outputIndex * subLength + frequencyIndex] = group[outputIndex];
            }
        }
    }
}
