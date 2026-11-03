package com.feizhela.headband.signal;

import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * 纯音：单一频率的实余弦
 * {@code x(t) = A * cos(2 * pi * f * t + phi)}。
 *
 * <p>纯音由振幅、{@link Frequency} 与相位（{@link Angle}，可用度或弧度）确定。
 * 允许负振幅（等价于相位加 pi）和负频率（反向旋转）；零频率表示常数，仍然可以
 * 求值，但没有周期。
 *
 * <p>除表示与求值外，本类提供：
 * <ul>
 *   <li>{@link #sample(double, int)} / {@link #sampleComplex(double, int)}：按采样率
 *       生成实样本或解析复样本；</li>
 *   <li>{@link #estimate(double[], double)}：从一个实信号中估计单频纯音的频率、
 *       振幅与相位（Hann 窗 FFT 粗估 + 黄金分割最小化最小二乘残差 + 含直流项的
 *       最小二乘定幅相）；</li>
 *   <li>{@link #estimate(double[], double, Frequency)}：在频率已知时只做最小二乘
 *       定幅相；</li>
 *   <li>{@link #residualRatio(double[], double)} / {@link #residualRms(double[], double)}：
 *       检验信号与纯音模型的吻合程度（直流偏置不计入纯音模型）。</li>
 * </ul>
 *
 * <p>估计过程复用对称分析窗、数组 FFT 与 {@code Analyse.Leak} 的谱峰插值实现。
 */
public final class Tone {

    private static final double TWO_PI = 2.0 * Math.PI;

    private final double amplitude;
    private final Frequency frequency;
    private final Angle phase;

    /**
     * @param amplitude 振幅；允许为负
     * @param frequency 频率
     * @param phase     相位
     */
    public Tone(final double amplitude, final Frequency frequency, final Angle phase) {
        if (!Double.isFinite(amplitude)) {
            throw new IllegalArgumentException("amplitude must be finite");
        }
        this.amplitude = amplitude;
        this.frequency = Objects.requireNonNull(frequency, "frequency");
        this.phase = Objects.requireNonNull(phase, "phase");
    }

    /**
     * @param amplitude 振幅；允许为负
     * @param hertz     频率，赫兹
     * @param phase     相位
     */
    public Tone(final double amplitude, final double hertz, final Angle phase) {
        this(amplitude, Frequency.fromHertz(hertz), phase);
    }

    /** @return 振幅 */
    public double getAmplitude() {
        return amplitude;
    }

    /** @return 频率 */
    public Frequency getFrequency() {
        return frequency;
    }

    /** @return 相位 */
    public Angle getPhase() {
        return phase;
    }

    /** @return 角频率，弧度每秒 */
    public double omega() {
        return frequency.radianPerSecond();
    }

    /**
     * @param time 时间，秒
     * @return 该时刻的实数值
     */
    public double evaluate(final double time) {
        return amplitude * Math.cos(angleAt(time));
    }

    /**
     * @param time 时间，秒
     * @return 该时刻的正弦分量 {@code A * sin(omega * t + phi)}
     */
    public double sine(final double time) {
        return amplitude * Math.sin(angleAt(time));
    }

    /**
     * @param time 时间，秒
     * @return 解析复信号 {@code A * exp(i * (omega * t + phi))}
     */
    public Complex evaluateComplex(final double time) {
        final double angle = angleAt(time);
        return new Complex(amplitude * Math.cos(angle), amplitude * Math.sin(angle));
    }

    /**
     * @return 零时刻的复振幅 {@code A * exp(i * phi)}
     */
    public Complex phasor() {
        final double angle = phase.toRadian().getValue();
        return new Complex(amplitude * Math.cos(angle), amplitude * Math.sin(angle));
    }

    /** @return 平均功率 {@code A * A / 2} */
    public double power() {
        return amplitude * amplitude / 2.0;
    }

    /** @return 周期，秒；零频率时抛出 {@link ArithmeticException} */
    public double period() {
        return frequency.period();
    }

    /**
     * 生成实样本，时刻为 {@code index / samplingRate}。
     *
     * @param samplingRate 采样率，必须为正
     * @param count        样本数，必须非负
     * @return 实样本
     */
    public double[] sample(final double samplingRate, final int count) {
        validateSamplingRate(samplingRate);
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        final double[] output = new double[count];
        for (int index = 0; index < count; index++) {
            output[index] = evaluate(index / samplingRate);
        }
        return output;
    }

    /**
     * 生成解析复样本，时刻为 {@code index / samplingRate}。
     *
     * @param samplingRate 采样率，必须为正
     * @param count        样本数，必须非负
     * @return 复样本
     */
    public Complex[] sampleComplex(final double samplingRate, final int count) {
        validateSamplingRate(samplingRate);
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        final Complex[] output = new Complex[count];
        for (int index = 0; index < count; index++) {
            output[index] = evaluateComplex(index / samplingRate);
        }
        return output;
    }

    /**
     * @return 对应的旋转复矢量
     */
    public Epicycle toEpicycle() {
        return new Epicycle(amplitude, frequency.radianPerSecond(), phase);
    }

    /**
     * @param epicycle 旋转复矢量
     * @return 对应的纯音
     */
    public static Tone fromEpicycle(final Epicycle epicycle) {
        Objects.requireNonNull(epicycle, "epicycle");
        return new Tone(epicycle.getAmplitude(),
                Frequency.fromRadianPerSecond(epicycle.getOmega()), epicycle.getPhase());
    }

    /**
     * 估计单频纯音的参数。
     *
     * <p>步骤：去均值后用 Hann 窗做 FFT，取非直流谱峰并用对数幅度抛物线插值得到粗频；
     * 在粗频上下各一个频率分辨单元内用黄金分割最小化含直流项的最小二乘残差，得到
     * 精频；最后在精频处用最小二乘确定振幅与相位。含直流项的拟合使结果不受信号
     * 直流偏置影响。
     *
     * @param signal       实信号
     * @param samplingRate 采样率
     * @return 估计出的纯音；全零信号返回零振幅、零频率的纯音
     */
    public static Tone estimate(final double[] signal, final double samplingRate) {
        validateSignal(signal, samplingRate);
        if (signal.length < 8) {
            throw new IllegalArgumentException("at least 8 samples are required");
        }
        final double mean = mean(signal);
        final double[] centered = new double[signal.length];
        double energy = 0.0;
        for (int index = 0; index < signal.length; index++) {
            centered[index] = signal[index] - mean;
            energy += centered[index] * centered[index];
        }
        if (energy <= 0.0) {
            return new Tone(0.0, 0.0, Angle.fromRadian(0.0));
        }

        final double[] window = Window.symmetric(Window.SymmetricType.HANN, signal.length);
        final Complex[] windowed = new Complex[signal.length];
        for (int index = 0; index < signal.length; index++) {
            windowed[index] = new Complex(centered[index] * window[index], 0.0);
        }
        final Complex[] spectrum = Sampler.forwardArray(windowed);

        final int half = signal.length / 2;
        int peakBin = 1;
        double peakMagnitude = spectrum[1].magnitude();
        for (int bin = 2; bin <= half; bin++) {
            final double magnitude = spectrum[bin].magnitude();
            if (magnitude > peakMagnitude) {
                peakMagnitude = magnitude;
                peakBin = bin;
            }
        }
        if (!(peakMagnitude > 0.0)) {
            return new Tone(0.0, 0.0, Angle.fromRadian(0.0));
        }

        final double binWidth = samplingRate / signal.length;
        final double offset = Analyse.Leak.interpolatePeakOffset(spectrum, peakBin);
        final double coarse = (peakBin + offset) * binWidth;
        final double lower = Math.max(1e-9, coarse - binWidth);
        final double upper = Math.min(samplingRate * 0.5 - 1e-9, coarse + binWidth);
        final double refined;
        if (upper > lower) {
            final DoubleUnaryOperator objective = trialFrequency ->
                    fitAt(signal, samplingRate, trialFrequency).residualEnergy;
            refined = goldenSectionMinimum(objective, lower, upper, 60);
        } else {
            refined = coarse;
        }
        return fit(signal, samplingRate, refined);
    }

    /**
     * 频率已知时估计纯音的振幅与相位（最小二乘，含直流项）。
     *
     * @param signal       实信号
     * @param samplingRate 采样率
     * @param frequency    已知频率
     * @return 估计出的纯音
     */
    public static Tone estimate(final double[] signal,
                                final double samplingRate,
                                final Frequency frequency) {
        validateSignal(signal, samplingRate);
        Objects.requireNonNull(frequency, "frequency");
        if (signal.length < 4) {
            throw new IllegalArgumentException("at least 4 samples are required");
        }
        return fit(signal, samplingRate, frequency.hertz());
    }

    /**
     * 频率已知时估计纯音的振幅与相位（最小二乘，含直流项）。
     *
     * @param signal       实信号
     * @param samplingRate 采样率
     * @param hertz        已知频率，赫兹
     * @return 估计出的纯音
     */
    public static Tone estimate(final double[] signal,
                                final double samplingRate,
                                final double hertz) {
        return estimate(signal, samplingRate, Frequency.fromHertz(hertz));
    }

    /**
     * @param signal       实信号
     * @param samplingRate 采样率
     * @return 残差能量与信号能量之比；纯音模型不含直流，直流偏置会计入残差
     */
    public double residualRatio(final double[] signal, final double samplingRate) {
        validateSignal(signal, samplingRate);
        double signalEnergy = 0.0;
        double residualEnergy = 0.0;
        for (int index = 0; index < signal.length; index++) {
            final double residual = signal[index] - evaluate(index / samplingRate);
            signalEnergy += signal[index] * signal[index];
            residualEnergy += residual * residual;
        }
        return residualEnergy / (signalEnergy + 1e-300);
    }

    /**
     * @param signal       实信号
     * @param samplingRate 采样率
     * @return 残差的均方根
     */
    public double residualRms(final double[] signal, final double samplingRate) {
        validateSignal(signal, samplingRate);
        double residualEnergy = 0.0;
        for (int index = 0; index < signal.length; index++) {
            final double residual = signal[index] - evaluate(index / samplingRate);
            residualEnergy += residual * residual;
        }
        return Math.sqrt(residualEnergy / signal.length);
    }

    // ---------------- 内部实现 ----------------

    private double angleAt(final double time) {
        if (!Double.isFinite(time)) {
            throw new IllegalArgumentException("time must be finite");
        }
        return frequency.radianPerSecond() * time + phase.toRadian().getValue();
    }

    private static Tone fit(final double[] signal,
                            final double samplingRate,
                            final double hertz) {
        final Fit fitted = fitAt(signal, samplingRate, hertz);
        return new Tone(fitted.amplitude, Frequency.fromHertz(hertz),
                Angle.fromRadian(fitted.phase));
    }

    /**
     * 拟合含直流项的模型 {@code c + a cos(omega t) + b sin(omega t)}，返回纯音部分
     * （振幅、相位）与最小残差能量。
     */
    private static Fit fitAt(final double[] signal,
                             final double samplingRate,
                             final double hertz) {
        if (!Double.isFinite(hertz) || hertz == 0.0) {
            throw new IllegalArgumentException("frequency must be finite and non-zero");
        }
        final int length = signal.length;
        final double omega = TWO_PI * hertz;
        double sum = length;
        double sumCosine = 0.0;
        double sumSine = 0.0;
        double sumCosineCosine = 0.0;
        double sumSineSine = 0.0;
        double sumCosineSine = 0.0;
        double sumSignal = 0.0;
        double sumSignalCosine = 0.0;
        double sumSignalSine = 0.0;
        double sumSignalSignal = 0.0;
        for (int index = 0; index < length; index++) {
            final double angle = omega * index / samplingRate;
            final double cosine = Math.cos(angle);
            final double sine = Math.sin(angle);
            final double value = signal[index];
            sumCosine += cosine;
            sumSine += sine;
            sumCosineCosine += cosine * cosine;
            sumSineSine += sine * sine;
            sumCosineSine += cosine * sine;
            sumSignal += value;
            sumSignalCosine += value * cosine;
            sumSignalSine += value * sine;
            sumSignalSignal += value * value;
        }
        final double determinant =
                sum * (sumCosineCosine * sumSineSine - sumCosineSine * sumCosineSine)
                - sumCosine * (sumCosine * sumSineSine - sumCosineSine * sumSine)
                + sumSine * (sumCosine * sumCosineSine - sumCosineCosine * sumSine);
        final double threshold = 1e-12 * (double) length * length * length;
        if (Math.abs(determinant) > threshold) {
            final double offset = (sumSignal
                    * (sumCosineCosine * sumSineSine - sumCosineSine * sumCosineSine)
                    - sumCosine
                    * (sumSignalCosine * sumSineSine - sumCosineSine * sumSignalSine)
                    + sumSine
                    * (sumSignalCosine * sumCosineSine - sumCosineCosine * sumSignalSine))
                    / determinant;
            final double cosineAmplitude = (sum
                    * (sumSignalCosine * sumSineSine - sumCosineSine * sumSignalSine)
                    - sumSignal
                    * (sumCosine * sumSineSine - sumCosineSine * sumSine)
                    + sumSine
                    * (sumCosine * sumSignalSine - sumSignalCosine * sumSine))
                    / determinant;
            final double sineAmplitude = (sum
                    * (sumCosineCosine * sumSignalSine - sumSignalCosine * sumCosineSine)
                    - sumCosine
                    * (sumCosine * sumSignalSine - sumSignalCosine * sumSine)
                    + sumSignal
                    * (sumCosine * sumCosineSine - sumCosineCosine * sumSine))
                    / determinant;
            final double amplitude = Math.hypot(cosineAmplitude, sineAmplitude);
            final double phase = Math.atan2(-sineAmplitude, cosineAmplitude);
            double residualEnergy = sumSignalSignal
                    - (offset * sumSignal
                    + cosineAmplitude * sumSignalCosine
                    + sineAmplitude * sumSignalSine);
            if (residualEnergy < 0.0) {
                residualEnergy = 0.0;
            }
            return new Fit(amplitude, phase, residualEnergy);
        }
        return fallbackFit(signal, samplingRate, hertz);
    }

    /**
     * 基函数近乎退化（极低频）时的两参数最小二乘相关回退。
     */
    private static Fit fallbackFit(final double[] signal,
                                   final double samplingRate,
                                   final double hertz) {
        final int length = signal.length;
        final double omega = TWO_PI * hertz;
        final double mean = mean(signal);
        double cosineCosine = 0.0;
        double sineSine = 0.0;
        double cosineSine = 0.0;
        double cosineSum = 0.0;
        double sineSum = 0.0;
        for (int index = 0; index < length; index++) {
            final double angle = omega * index / samplingRate;
            final double cosine = Math.cos(angle);
            final double sine = Math.sin(angle);
            final double value = signal[index] - mean;
            cosineCosine += cosine * cosine;
            sineSine += sine * sine;
            cosineSine += cosine * sine;
            cosineSum += value * cosine;
            sineSum += value * sine;
        }
        final double determinant = cosineCosine * sineSine - cosineSine * cosineSine;
        double amplitude;
        double phase;
        if (Math.abs(determinant) > 1e-12 * (double) length * length) {
            final double cosineAmplitude =
                    (cosineSum * sineSine - sineSum * cosineSine) / determinant;
            final double sineAmplitude =
                    (sineSum * cosineCosine - cosineSum * cosineSine) / determinant;
            amplitude = Math.hypot(cosineAmplitude, sineAmplitude);
            phase = Math.atan2(-sineAmplitude, cosineAmplitude);
        } else {
            final double stepAngle = -omega / samplingRate;
            final Complex step = new Complex(Math.cos(stepAngle), Math.sin(stepAngle));
            Complex rotation = new Complex(1.0, 0.0);
            double real = 0.0;
            double imaginary = 0.0;
            for (int index = 0; index < length; index++) {
                final double value = signal[index] - mean;
                real += value * rotation.getReal();
                imaginary += value * rotation.getImaginary();
                rotation = rotation.multiply(step);
            }
            amplitude = 2.0 * Math.hypot(real, imaginary) / length;
            phase = Math.atan2(imaginary, real);
        }
        double residualEnergy = 0.0;
        for (int index = 0; index < length; index++) {
            final double residual = signal[index]
                    - amplitude * Math.cos(omega * index / samplingRate + phase);
            residualEnergy += residual * residual;
        }
        return new Fit(amplitude, phase, residualEnergy);
    }

    private static double goldenSectionMinimum(final DoubleUnaryOperator objective,
                                               final double lowerBound,
                                               final double upperBound,
                                               final int iterations) {
        final double goldenRatio = (Math.sqrt(5.0) - 1.0) / 2.0;
        double lower = lowerBound;
        double upper = upperBound;
        double firstPoint = upper - goldenRatio * (upper - lower);
        double secondPoint = lower + goldenRatio * (upper - lower);
        double firstValue = objective.applyAsDouble(firstPoint);
        double secondValue = objective.applyAsDouble(secondPoint);
        for (int iteration = 0; iteration < iterations; iteration++) {
            if (firstValue < secondValue) {
                upper = secondPoint;
                secondPoint = firstPoint;
                secondValue = firstValue;
                firstPoint = upper - goldenRatio * (upper - lower);
                firstValue = objective.applyAsDouble(firstPoint);
            } else {
                lower = firstPoint;
                firstPoint = secondPoint;
                firstValue = secondValue;
                secondPoint = lower + goldenRatio * (upper - lower);
                secondValue = objective.applyAsDouble(secondPoint);
            }
        }
        return 0.5 * (lower + upper);
    }

    private static double mean(final double[] signal) {
        double total = 0.0;
        for (double value : signal) {
            total += value;
        }
        return total / signal.length;
    }

    private static void validateSamplingRate(final double samplingRate) {
        if (!Double.isFinite(samplingRate) || samplingRate <= 0.0) {
            throw new IllegalArgumentException("samplingRate must be positive and finite");
        }
    }

    private static void validateSignal(final double[] signal, final double samplingRate) {
        Objects.requireNonNull(signal, "signal");
        if (signal.length == 0) {
            throw new IllegalArgumentException("signal must not be empty");
        }
        validateSamplingRate(samplingRate);
        for (double value : signal) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("signal must contain only finite values");
            }
        }
    }

    /** 最小二乘拟合的中间结果。 */
    private static final class Fit {

        private final double amplitude;
        private final double phase;
        private final double residualEnergy;

        private Fit(final double amplitude, final double phase, final double residualEnergy) {
            this.amplitude = amplitude;
            this.phase = phase;
            this.residualEnergy = residualEnergy;
        }
    }
}
