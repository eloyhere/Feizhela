package com.feizhela.headband.signal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.DoubleUnaryOperator;

/**
 * 历法式频谱精估（calendar spectral）：{@code periodicity/leap.cpp} 的 Java 移植。
 *
 * <p>核心流程与 leap.cpp 的 {@code Spectral::estimate} 一致：
 * <ol>
 *   <li>用对称 Hann 窗做一次粗估，找出幅度最大的若干谱峰；</li>
 *   <li>按“调日法”选择分析窗长（{@link Leap#selectAnalysisWindow}）；</li>
 *   <li>换用 Kaiser 等窗做闰余校正，对每个谱峰做黄金分割频率精修，并按相关相位提取
 *       圆振幅与初相；</li>
 *   <li>用残差比验历闭环；未通过时增大 Kaiser β 并增加一个分量重试一次。</li>
 * </ol>
 *
 * <p>为了能逐段核对 leap.cpp，本文件保留了原实现的类结构：{@link Window}、
 * {@link Epicycle}、{@link Waveform}、{@link Sample}、{@link Sampler}、
 * {@link Spectrum}、{@link Leak}、{@link Leap}、{@link Spectral}。复数运算复用
 * signal 包中不可变的 {@link Complex} 值类型，窗函数和数组 FFT 委托给包内共享 API。
 * 嵌套类型保留旧契约；公共入口返回包内不可变 Waveform 和 Tone 模型。
 *
 * <p>类名与文件名保持 PascalCase，与包内其它类型一致。
 */
public final class Analyse {

    /** 圆周率，与 leap.cpp 中的常量一致。 */
    public static final double PI = Math.PI;

    private Analyse() {
    }

    /**
     * Anti-aliases/resamples raw data and runs the calendar estimator with its legacy defaults.
     * Public models are immutable snapshots; estimation and residual verification use raw phases
     * internally. Angle normalization at this result boundary can introduce evaluation roundoff.
     */
    public static Result estimateFromRaw(final double[] rawSignal,
                                         final double rawSamplingRate,
                                         final double analysisSamplingRate,
                                         final int numberOfComponents) {
        return new Result(Spectral.estimateFromRaw(rawSignal, rawSamplingRate,
                analysisSamplingRate, numberOfComponents));
    }

    /** Estimates data already sampled at the requested analysis rate. */
    public static Result estimate(final double[] signal, final double samplingRate,
                                  final int numberOfComponents) {
        return new Result(Spectral.estimate(signal, samplingRate, numberOfComponents));
    }

    /** Immutable public-model view of a completed analysis. */
    public static final class Result {
        private final com.feizhela.headband.signal.Waveform waveform;
        private final List<Tone> tones;
        private final int analysisWindow;
        private final double residualRatio;
        private final boolean verified;

        private Result(final Spectral.Result legacy) {
            final List<com.feizhela.headband.signal.Epicycle> components = new ArrayList<>();
            final List<Tone> models = new ArrayList<>();
            for (Epicycle component : legacy.getWaveform().getEpicycles()) {
                final com.feizhela.headband.signal.Epicycle model =
                        new com.feizhela.headband.signal.Epicycle(component.amplitude,
                                component.omega, Angle.fromRadian(component.phase));
                components.add(model);
                models.add(Tone.fromEpicycle(model));
            }
            waveform = new com.feizhela.headband.signal.Waveform(components);
            tones = Collections.unmodifiableList(models);
            analysisWindow = legacy.getAnalysisWindow();
            residualRatio = legacy.getResidualRatio();
            verified = legacy.isVerified();
        }

        public com.feizhela.headband.signal.Waveform getWaveform() { return waveform; }
        public List<Tone> getTones() { return tones; }
        public int getAnalysisWindow() { return analysisWindow; }
        public double getResidualRatio() { return residualRatio; }
        public boolean isVerified() { return verified; }
    }

    // =================================================================
    // Window
    // =================================================================

    /**
     * 六类分析窗，窗系数与 leap.cpp 相同：对称窗，角度分母为 {@code length - 1}。
     *
     * <p>注意：包内 {@link Window} 使用的是周期窗（分母为 {@code length}），两者不可
     * 混用；本类保留 leap.cpp 的定义以复现原算法。
     */
    public static final class Window {

        /** 窗类型，对应 leap.cpp 的 {@code Window::Type}。 */
        public enum Type {
            RECTANGULAR,
            HANN,
            HAMMING,
            BLACKMAN,
            KAISER,
            FLAT_TOP
        }

        private Window() {
        }

        /**
         * 零阶第一类修正贝塞尔函数，级数展开至 64 项。
         *
         * @param value 自变量
         * @return I0(value)
         */
        public static double besselI0(final double value) {
            return com.feizhela.headband.signal.Window.besselI0(value);
        }

        /**
         * 生成窗系数，Kaiser 参数取默认值 8。
         *
         * @param type   窗类型
         * @param length 窗长
         * @return 长度为 {@code length} 的窗系数
         */
        public static double[] make(final Type type, final int length) {
            return make(type, length, 8.0);
        }

        /**
         * 生成窗系数。
         *
         * @param type      窗类型
         * @param length    窗长
         * @param parameter Kaiser 窗的 β 参数，其余窗忽略
         * @return 长度为 {@code length} 的窗系数
         */
        public static double[] make(final Type type, final int length, final double parameter) {
            Objects.requireNonNull(type, "type");
            return com.feizhela.headband.signal.Window.symmetric(
                    com.feizhela.headband.signal.Window.SymmetricType.valueOf(type.name()),
                    length, parameter);
        }

        /**
         * @param window 窗系数
         * @return 窗系数之和
         */
        public static double sum(final double[] window) {
            Objects.requireNonNull(window, "window");
            double total = 0.0;
            for (double value : window) {
                total += value;
            }
            return total;
        }
    }

    // =================================================================
    // Epicycle
    // =================================================================

    /**
     * 一个旋转复矢量 {@code A * exp(i * (omega * x + phase))}，对应 leap.cpp 的
     * {@code Epicycle}。振幅、角频率与初相均保留原始 double 值。
     */
    public static final class Epicycle {

        /** 振幅。 */
        public final double amplitude;

        /** 角频率，弧度每秒。 */
        public final double omega;

        /** 初相，弧度。 */
        public final double phase;

        /** 零矢量。 */
        public Epicycle() {
            this(0.0, 0.0, 0.0);
        }

        /**
         * @param amplitude 振幅
         * @param omega     角频率
         * @param phase     初相
         */
        public Epicycle(final double amplitude, final double omega, final double phase) {
            if (!Double.isFinite(amplitude)) {
                throw new IllegalArgumentException("amplitude must be finite");
            }
            if (!Double.isFinite(omega)) {
                throw new IllegalArgumentException("omega must be finite");
            }
            if (!Double.isFinite(phase)) {
                throw new IllegalArgumentException("phase must be finite");
            }
            this.amplitude = amplitude;
            this.omega = omega;
            this.phase = phase;
        }

        /**
         * @param position 自变量
         * @return 该位置上的复数值
         */
        public Complex evaluate(final double position) {
            if (!Double.isFinite(position)) {
                throw new IllegalArgumentException("position must be finite");
            }
            final double angle = omega * position + phase;
            return new Complex(amplitude * Math.cos(angle), amplitude * Math.sin(angle));
        }

        /**
         * @return 解析导数对应的 Epicycle
         */
        public Epicycle derivate() {
            return new Epicycle(amplitude * omega, omega, phase + PI / 2.0);
        }

        /**
         * @param position 自变量
         * @return 该位置上的解析导数值
         */
        public Complex derivate(final double position) {
            if (!Double.isFinite(position)) {
                throw new IllegalArgumentException("position must be finite");
            }
            final double angle = omega * position + phase;
            return new Complex(-amplitude * omega * Math.sin(angle),
                    amplitude * omega * Math.cos(angle));
        }

        /**
         * @return 零初值原函数；{@code |omega|} 极小时返回零矢量
         */
        public Epicycle integral() {
            if (Math.abs(omega) < 1e-300) {
                return new Epicycle(0.0, 0.0, 0.0);
            }
            return new Epicycle(amplitude / omega, omega, phase - PI / 2.0);
        }

        /**
         * @param start 区间起点
         * @param end   区间终点
         * @return 区间上的平均导数值
         */
        public Complex derivate(final double start, final double end) {
            final double span = end - start;
            if (Math.abs(span) < 1e-300) {
                return derivate(start);
            }
            return evaluate(end).subtract(evaluate(start)).divide(span);
        }
    }

    // =================================================================
    // Waveform
    // =================================================================

    /** 有限个旋转复矢量之和，对应 leap.cpp 的 {@code Waveform}。 */
    public static final class Waveform {

        private final List<Epicycle> epicycles = new ArrayList<>();

        /** 空波形。 */
        public Waveform() {
        }

        /**
         * @param values 初始分量
         */
        public Waveform(final Epicycle... values) {
            if (values != null) {
                for (Epicycle value : values) {
                    add(value);
                }
            }
        }

        /**
         * @param epicycle 追加的分量
         */
        public void add(final Epicycle epicycle) {
            epicycles.add(Objects.requireNonNull(epicycle, "epicycle"));
        }

        /** @return 分量个数 */
        public int size() {
            return epicycles.size();
        }

        /**
         * @param index 索引
         * @return 指定分量
         */
        public Epicycle get(final int index) {
            return epicycles.get(index);
        }

        /** @return 只读分量列表 */
        public List<Epicycle> getEpicycles() {
            return Collections.unmodifiableList(epicycles);
        }

        /**
         * @param position 自变量
         * @return 波形在该位置的值
         */
        public Complex evaluate(final double position) {
            double real = 0.0;
            double imaginary = 0.0;
            for (Epicycle epicycle : epicycles) {
                final Complex value = epicycle.evaluate(position);
                real += value.getReal();
                imaginary += value.getImaginary();
            }
            return new Complex(real, imaginary);
        }

        /** @return 逐项求导后的波形 */
        public Waveform derivate() {
            final Waveform result = new Waveform();
            for (Epicycle epicycle : epicycles) {
                result.add(epicycle.derivate());
            }
            return result;
        }

        /**
         * @param position 自变量
         * @return 波形在该位置的解析导数值
         */
        public Complex derivate(final double position) {
            double real = 0.0;
            double imaginary = 0.0;
            for (Epicycle epicycle : epicycles) {
                final Complex value = epicycle.derivate(position);
                real += value.getReal();
                imaginary += value.getImaginary();
            }
            return new Complex(real, imaginary);
        }

        /** @return 逐项积分后的波形 */
        public Waveform integral() {
            final Waveform result = new Waveform();
            for (Epicycle epicycle : epicycles) {
                result.add(epicycle.integral());
            }
            return result;
        }

        /**
         * @param start 区间起点
         * @param end   区间终点
         * @return 区间上的平均导数值
         */
        public Complex derivate(final double start, final double end) {
            double real = 0.0;
            double imaginary = 0.0;
            for (Epicycle epicycle : epicycles) {
                final Complex value = epicycle.derivate(start, end);
                real += value.getReal();
                imaginary += value.getImaginary();
            }
            return new Complex(real, imaginary);
        }
    }

    // =================================================================
    // Sample
    // =================================================================

    /**
     * 一段采样计划：持续时间与采样点数，对应 leap.cpp 的 {@code Sample}。
     *
     * <p>{@code count} 是采样点数（不是速率），采样率由 {@code count / duration}
     * 得到。
     */
    public static final class Sample {

        /** 持续时间，秒。 */
        public final double duration;

        /** 采样点数。 */
        public final double count;

        /** 空采样段。 */
        public Sample() {
            this(0.0, 0.0);
        }

        /**
         * @param duration 持续时间
         * @param count    采样点数
         */
        public Sample(final double duration, final double count) {
            if (!Double.isFinite(duration) || duration < 0.0) {
                throw new IllegalArgumentException("duration must be non-negative and finite");
            }
            if (!Double.isFinite(count) || count < 0.0) {
                throw new IllegalArgumentException("count must be non-negative and finite");
            }
            this.duration = duration;
            this.count = count;
        }

        /** @return 采样率；持续时间为零时返回 0 */
        public double samplingRate() {
            if (duration > 0.0) {
                return count / duration;
            }
            return 0.0;
        }

        /** @return 采样间隔；采样率为零时返回 0 */
        public double samplingInterval() {
            final double rate = samplingRate();
            if (rate > 0.0) {
                return 1.0 / rate;
            }
            return 0.0;
        }

        /** @return 四舍五入后的采样点数 */
        public int pointCount() {
            final long rounded = Math.round(count);
            if (rounded < 0) {
                return 0;
            }
            if (rounded > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("too many sample points");
            }
            return (int) rounded;
        }
    }

    // =================================================================
    // Sampler
    // =================================================================

    /**
     * 采样与变换器，对应 leap.cpp 的 {@code Sampler}：既可以按 {@link Sample} 计划
     * 从 {@link Waveform} 取值，也可以直接注入样本（{@link #setDirectData}）。
     */
    public static final class Sampler {

        private final List<Sample> samples = new ArrayList<>();
        private Waveform source = new Waveform();
        private Complex[] directData = new Complex[0];

        /** 空采样器。 */
        public Sampler() {
        }

        /**
         * @param values 采样段
         */
        public Sampler(final Sample... values) {
            if (values != null) {
                for (Sample value : values) {
                    add(value);
                }
            }
        }

        /**
         * @param values 采样段
         */
        public Sampler(final List<Sample> values) {
            if (values != null) {
                for (Sample value : values) {
                    add(value);
                }
            }
        }

        /**
         * @param sourceWaveform 信号源
         * @param values         采样段
         */
        public Sampler(final Waveform sourceWaveform, final Sample... values) {
            this(values);
            setSource(sourceWaveform);
        }

        /**
         * @param sourceWaveform 信号源
         * @param values         采样段
         */
        public Sampler(final Waveform sourceWaveform, final List<Sample> values) {
            this(values);
            setSource(sourceWaveform);
        }

        /**
         * @param sample 追加的采样段
         */
        public void add(final Sample sample) {
            samples.add(Objects.requireNonNull(sample, "sample"));
        }

        /**
         * @param waveform 信号源
         */
        public void setSource(final Waveform waveform) {
            source = Objects.requireNonNull(waveform, "waveform");
        }

        /**
         * 注入直接样本；注入后 {@link #gatherComplex()} / {@link #gatherReal()}
         * 不再使用 {@code source}。
         *
         * @param data 样本
         */
        public void setDirectData(final Complex[] data) {
            Objects.requireNonNull(data, "data");
            final Complex[] copy = new Complex[data.length];
            for (int index = 0; index < data.length; index++) {
                copy[index] = Objects.requireNonNull(data[index], "data[" + index + "]");
            }
            directData = copy;
        }

        /** 清除直接样本。 */
        public void clearDirectData() {
            directData = new Complex[0];
        }

        /** @return 采样段数目 */
        public int segmentCount() {
            return samples.size();
        }

        /** @return 总采样点数 */
        public int totalPointCount() {
            long total = 0;
            for (Sample sample : samples) {
                total += sample.pointCount();
            }
            if (total > Integer.MAX_VALUE) {
                throw new IllegalStateException("too many sample points");
            }
            return (int) total;
        }

        /** @return 总持续时间 */
        public double totalDuration() {
            double total = 0.0;
            for (Sample sample : samples) {
                total += sample.duration;
            }
            return total;
        }

        /** @return 每个采样点的时刻 */
        public double[] sampleTimes() {
            final double[] times = new double[totalPointCount()];
            int cursor = 0;
            double currentTime = 0.0;
            for (Sample sample : samples) {
                final int pointCount = sample.pointCount();
                final double interval = sample.samplingInterval();
                for (int pointIndex = 0; pointIndex < pointCount; pointIndex++) {
                    times[cursor++] = currentTime + pointIndex * interval;
                }
                currentTime += sample.duration;
            }
            return times;
        }

        /** @return 复样本 */
        public Complex[] gatherComplex() {
            if (directData.length > 0) {
                return directData.clone();
            }
            final double[] times = sampleTimes();
            final Complex[] output = new Complex[times.length];
            for (int index = 0; index < times.length; index++) {
                output[index] = source.evaluate(times[index]);
            }
            return output;
        }

        /** @return 实样本 */
        public double[] gatherReal() {
            if (directData.length > 0) {
                final double[] output = new double[directData.length];
                for (int index = 0; index < directData.length; index++) {
                    output[index] = directData[index].getReal();
                }
                return output;
            }
            final double[] times = sampleTimes();
            final double[] output = new double[times.length];
            for (int index = 0; index < times.length; index++) {
                output[index] = source.evaluate(times[index]).getReal();
            }
            return output;
        }

        // ---------------- 对外变换 ----------------

        /** @return 基 2 FFT（正变换） */
        public Complex[] radix2() {
            return radix2(false);
        }

        /**
         * @param inverse 是否逆变换
         * @return 基 2 FFT
         */
        public Complex[] radix2(final boolean inverse) {
            return com.feizhela.headband.signal.Sampler.radixTransformArray(gatherComplex(), 2, inverse);
        }

        /** @return 基 3 FFT（正变换） */
        public Complex[] radix3() {
            return radix3(false);
        }

        /**
         * @param inverse 是否逆变换
         * @return 基 3 FFT
         */
        public Complex[] radix3(final boolean inverse) {
            return com.feizhela.headband.signal.Sampler.radixTransformArray(gatherComplex(), 3, inverse);
        }

        /** @return 基 5 FFT（正变换） */
        public Complex[] radix5() {
            return radix5(false);
        }

        /**
         * @param inverse 是否逆变换
         * @return 基 5 FFT
         */
        public Complex[] radix5(final boolean inverse) {
            return com.feizhela.headband.signal.Sampler.radixTransformArray(gatherComplex(), 5, inverse);
        }

        /** @return 基 7 FFT（正变换） */
        public Complex[] radix7() {
            return radix7(false);
        }

        /**
         * @param inverse 是否逆变换
         * @return 基 7 FFT
         */
        public Complex[] radix7(final boolean inverse) {
            return com.feizhela.headband.signal.Sampler.radixTransformArray(gatherComplex(), 7, inverse);
        }

        /** @return 混合基 FFT（正变换） */
        public Complex[] mixedRadix() {
            return mixedRadix(false);
        }

        /**
         * @param inverse 是否逆变换
         * @return 混合基 FFT
         */
        public Complex[] mixedRadix(final boolean inverse) {
            return com.feizhela.headband.signal.Sampler.mixedTransformArray(gatherComplex(), inverse);
        }

        /** @return Bluestein FFT（正变换） */
        public Complex[] blueStein() {
            return blueStein(false);
        }

        /**
         * @param inverse 是否逆变换
         * @return Bluestein FFT
         */
        public Complex[] blueStein(final boolean inverse) {
            return com.feizhela.headband.signal.Sampler.bluesteinTransformArray(gatherComplex(), inverse);
        }

        /** @return 离散傅里叶变换（正变换） */
        public Complex[] discreteFourierTransform() {
            return discreteFourierTransform(false);
        }

        /**
         * @param inverse 是否逆变换
         * @return 离散傅里叶变换
         */
        public Complex[] discreteFourierTransform(final boolean inverse) {
            return com.feizhela.headband.signal.Sampler.discreteTransformArray(gatherComplex(), inverse);
        }

        /** @return 自动选择算法的最快傅里叶变换（正变换） */
        public Complex[] fastFourierTransform() {
            return fastFourierTransform(false);
        }

        /**
         * 长度是 2 的幂时用基 2；是 2/3/5/7 的平滑数时用混合基；否则用 Bluestein。
         *
         * @param inverse 是否逆变换
         * @return 傅里叶变换
         */
        public Complex[] fastFourierTransform(final boolean inverse) {
            return com.feizhela.headband.signal.Sampler.transformArray(gatherComplex(), inverse);
        }

        /** @return 一级 Haar 小波变换 */
        public Complex[] waveletTransform() {
            final double[] input = gatherReal();
            final int length = input.length;
            final Complex[] output = new Complex[length];
            Arrays.fill(output, new Complex());
            if (length == 0) {
                return output;
            }
            final int half = length / 2;
            final double rootTwo = Math.sqrt(2.0);
            for (int index = 0; index < half; index++) {
                final double firstValue = input[2 * index];
                final double secondValue = input[2 * index + 1];
                output[index] = new Complex((firstValue + secondValue) / rootTwo, 0.0);
                output[half + index] = new Complex((firstValue - secondValue) / rootTwo, 0.0);
            }
            if (length % 2 != 0) {
                output[length - 1] = new Complex(input[length - 1], 0.0);
            }
            return output;
        }

        /** @return 离散拉普拉斯变换 */
        public Complex[] laplacianTransform() {
            final double[] input = gatherReal();
            final int length = input.length;
            final Complex[] output = new Complex[length];
            Arrays.fill(output, new Complex());
            if (length == 0) {
                return output;
            }
            if (length == 1) {
                output[0] = new Complex();
                return output;
            }
            output[0] = new Complex(input[1] - input[0], 0.0);
            for (int index = 1; index + 1 < length; index++) {
                final double value = input[index - 1] - 2.0 * input[index] + input[index + 1];
                output[index] = new Complex(value, 0.0);
            }
            output[length - 1] = new Complex(input[length - 2] - input[length - 1], 0.0);
            return output;
        }

        // ---------------- 内部实现 ----------------


    }

    // =================================================================
    // Spectrum
    // =================================================================

    /** 频率与复系数成对保存的频谱，对应 leap.cpp 的 {@code Spectrum}。 */
    public static final class Spectrum {

        private final List<Double> frequencies = new ArrayList<>();
        private final List<Complex> coefficients = new ArrayList<>();

        /** @return 频点个数 */
        public int size() {
            return frequencies.size();
        }

        /** @return 是否为空 */
        public boolean isEmpty() {
            return frequencies.isEmpty();
        }

        /** 清空频谱。 */
        public void clear() {
            frequencies.clear();
            coefficients.clear();
        }

        /**
         * @param frequency  频率
         * @param coefficient 复系数
         */
        public void add(final double frequency, final Complex coefficient) {
            if (!Double.isFinite(frequency)) {
                throw new IllegalArgumentException("frequency must be finite");
            }
            frequencies.add(frequency);
            coefficients.add(Objects.requireNonNull(coefficient, "coefficient"));
        }

        /**
         * @param index 索引
         * @return 频率
         */
        public double getFrequency(final int index) {
            return frequencies.get(index);
        }

        /**
         * @param index 索引
         * @return 复系数
         */
        public Complex getCoefficient(final int index) {
            return coefficients.get(index);
        }

        /** @return 幅度序列 */
        public double[] magnitudes() {
            final double[] output = new double[size()];
            for (int index = 0; index < size(); index++) {
                output[index] = coefficients.get(index).magnitude();
            }
            return output;
        }
    }

    // =================================================================
    // Leak
    // =================================================================

    /** 泄漏与谱峰校正工具，对应 leap.cpp 的 {@code Leak}。 */
    public static final class Leak {

        private Leak() {
        }

        /**
         * 用对数幅度的抛物线插值估计谱峰相对整数频点的偏移，结果限制在 ±0.5。
         *
         * @param coefficients 复频谱
         * @param peakIndex    谱峰频点
         * @return 频率偏移
         */
        public static double interpolatePeakOffset(final Complex[] coefficients, final int peakIndex) {
            Objects.requireNonNull(coefficients, "coefficients");
            if (peakIndex <= 0 || peakIndex + 1 >= coefficients.length) {
                return 0.0;
            }
            final double leftValue = Math.log(coefficients[peakIndex - 1].magnitude() + 1e-300);
            final double centerValue = Math.log(coefficients[peakIndex].magnitude() + 1e-300);
            final double rightValue = Math.log(coefficients[peakIndex + 1].magnitude() + 1e-300);
            final double denominator = leftValue - 2.0 * centerValue + rightValue;
            if (Math.abs(denominator) < 1e-12) {
                return 0.0;
            }
            double offset = 0.5 * (leftValue - rightValue) / denominator;
            if (offset < -0.5) {
                offset = -0.5;
            }
            if (offset > 0.5) {
                offset = 0.5;
            }
            return offset;
        }

        /**
         * @param magnitudes 幅度谱
         * @param peakIndex  谱峰频点
         * @param halfWidth  主瓣半宽（频点数）
         * @return 最大旁瓣与主瓣峰值之比
         */
        public static double sidelobeRatio(final double[] magnitudes,
                                           final int peakIndex,
                                           final int halfWidth) {
            Objects.requireNonNull(magnitudes, "magnitudes");
            if (magnitudes.length == 0 || peakIndex < 0 || peakIndex >= magnitudes.length) {
                return 0.0;
            }
            final double peakValue = magnitudes[peakIndex];
            if (peakValue <= 0.0) {
                return 0.0;
            }
            double sideValue = 0.0;
            for (int index = 0; index < magnitudes.length; index++) {
                final boolean insideMainLobe =
                        (index + halfWidth >= peakIndex) && (index <= peakIndex + halfWidth);
                if (!insideMainLobe && magnitudes[index] > sideValue) {
                    sideValue = magnitudes[index];
                }
            }
            return sideValue / peakValue;
        }

        /**
         * @param magnitudes 幅度谱
         * @param peakIndex  谱峰频点
         * @param halfWidth  主瓣半宽（频点数）
         * @return 主瓣外能量占全部能量的比例
         */
        public static double leakageRatio(final double[] magnitudes,
                                          final int peakIndex,
                                          final int halfWidth) {
            Objects.requireNonNull(magnitudes, "magnitudes");
            double totalEnergy = 0.0;
            double mainLobeEnergy = 0.0;
            for (int index = 0; index < magnitudes.length; index++) {
                final double energy = magnitudes[index] * magnitudes[index];
                totalEnergy += energy;
                final boolean insideMainLobe =
                        (index + halfWidth >= peakIndex) && (index <= peakIndex + halfWidth);
                if (insideMainLobe) {
                    mainLobeEnergy += energy;
                }
            }
            return (totalEnergy - mainLobeEnergy) / (totalEnergy + 1e-300);
        }
    }

    // =================================================================
    // Leap
    // =================================================================

    /** 调日选窗与连分数工具，对应 leap.cpp 的 {@code Leap}。 */
    public static final class Leap {

        private Leap() {
        }

        /**
         * 连分数展开，返回不超过最大分母的最佳近似分子/分母。
         *
         * @param ratio              归一化频率（通常在 (0, 0.5) 内）
         * @param maximumDenominator 允许的最大分母
         * @return {@code {分子, 分母}}；分母为零时返回 {@code {0, 1}}
         */
        public static long[] continuedFraction(final double ratio, final long maximumDenominator) {
            long previousNumerator = 0L;
            long previousDenominator = 1L;
            long currentNumerator = 1L;
            long currentDenominator = 0L;
            double value = ratio;
            for (int iteration = 0; iteration < 64; iteration++) {
                final long integerPart = (long) Math.floor(value);
                final long nextNumerator = integerPart * currentNumerator + previousNumerator;
                final long nextDenominator = integerPart * currentDenominator + previousDenominator;
                if (nextDenominator > maximumDenominator || nextDenominator <= 0L) {
                    break;
                }
                previousNumerator = currentNumerator;
                previousDenominator = currentDenominator;
                currentNumerator = nextNumerator;
                currentDenominator = nextDenominator;
                final double fractionalPart = value - (double) integerPart;
                if (fractionalPart < 1e-12) {
                    break;
                }
                value = 1.0 / fractionalPart;
            }
            if (currentDenominator == 0L) {
                return new long[]{0L, 1L};
            }
            return new long[]{currentNumerator, currentDenominator};
        }

        /**
         * 按“调日法”选择分析窗长：候选窗长取各频率连分数分母的整数倍，并加上
         * 最大窗长附近的若干值；代价是所有分量在一个窗内完成整周期的程度。
         *
         * @param frequencies  粗估频率
         * @param weights      各频率权重
         * @param samplingRate 采样率
         * @param minimumWindow 最小窗长
         * @param maximumWindow 最大窗长
         * @return 选中的分析窗长
         */
        public static int selectAnalysisWindow(final double[] frequencies,
                                               final double[] weights,
                                               final double samplingRate,
                                               final int minimumWindow,
                                               final int maximumWindow) {
            Objects.requireNonNull(frequencies, "frequencies");
            Objects.requireNonNull(weights, "weights");
            if (frequencies.length != weights.length) {
                throw new IllegalArgumentException("frequencies and weights must have the same length");
            }
            if (!Double.isFinite(samplingRate) || samplingRate <= 0.0) {
                throw new IllegalArgumentException("samplingRate must be positive and finite");
            }
            if (frequencies.length == 0 || maximumWindow <= minimumWindow) {
                return maximumWindow;
            }
            final TreeSet<Integer> candidates = new TreeSet<>();
            for (int componentIndex = 0; componentIndex < frequencies.length; componentIndex++) {
                final double normalizedFrequency = frequencies[componentIndex] / samplingRate;
                if (normalizedFrequency <= 0.0 || normalizedFrequency >= 0.5) {
                    continue;
                }
                final long[] approximation =
                        continuedFraction(normalizedFrequency, maximumWindow);
                final long denominator = approximation[1];
                if (denominator <= 0L) {
                    continue;
                }
                for (long multiplier = 1L;
                     multiplier * denominator <= maximumWindow;
                     multiplier++) {
                    final long candidate = multiplier * denominator;
                    if (candidate >= minimumWindow && candidate <= Integer.MAX_VALUE) {
                        candidates.add((int) candidate);
                    }
                }
            }
            for (int offset = 0; offset < 32 && maximumWindow > minimumWindow + offset; offset++) {
                candidates.add(maximumWindow - offset);
            }

            int bestWindow = maximumWindow;
            double bestCost = Double.MAX_VALUE;
            for (int candidate : candidates) {
                if (candidate < minimumWindow || candidate > maximumWindow) {
                    continue;
                }
                double cost = 0.0;
                double weightSum = 0.0;
                for (int componentIndex = 0; componentIndex < frequencies.length; componentIndex++) {
                    final double cycles = frequencies[componentIndex] * candidate / samplingRate;
                    final double fractional = cycles - Math.floor(cycles);
                    final double distance = Math.min(fractional, 1.0 - fractional);
                    cost += weights[componentIndex] * distance;
                    weightSum += weights[componentIndex];
                }
                if (weightSum > 0.0) {
                    cost /= weightSum;
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    bestWindow = candidate;
                }
            }
            return bestWindow;
        }
    }

    // =================================================================
    // Spectral
    // =================================================================

    /** 历法式频谱精估，对应 leap.cpp 的 {@code Spectral}。 */
    public static final class Spectral {

        private Spectral() {
        }

        /** 精估选项，默认值与 leap.cpp 一致（Kaiser β=8、残差阈值 1e-3、开启验历）。 */
        public static final class Options {

            private final Window.Type windowType;
            private final double kaiserBeta;
            private final double residualThreshold;
            private final boolean enableVerification;

            /** 使用 leap.cpp 的默认选项。 */
            public Options() {
                this(Window.Type.KAISER, 8.0, 1e-3, true);
            }

            /**
             * @param windowType         窗类型
             * @param kaiserBeta         Kaiser 窗 β
             * @param residualThreshold  残差比阈值
             * @param enableVerification 是否开启验历重试
             */
            public Options(final Window.Type windowType,
                           final double kaiserBeta,
                           final double residualThreshold,
                           final boolean enableVerification) {
                this.windowType = Objects.requireNonNull(windowType, "windowType");
                if (!Double.isFinite(kaiserBeta) || kaiserBeta < 0.0) {
                    throw new IllegalArgumentException("kaiserBeta must be non-negative and finite");
                }
                if (!Double.isFinite(residualThreshold) || residualThreshold < 0.0) {
                    throw new IllegalArgumentException("residualThreshold must be non-negative and finite");
                }
                this.kaiserBeta = kaiserBeta;
                this.residualThreshold = residualThreshold;
                this.enableVerification = enableVerification;
            }

            /** @return 窗类型 */
            public Window.Type getWindowType() {
                return windowType;
            }

            /** @return Kaiser 窗 β */
            public double getKaiserBeta() {
                return kaiserBeta;
            }

            /** @return 残差比阈值 */
            public double getResidualThreshold() {
                return residualThreshold;
            }

            /** @return 是否开启验历重试 */
            public boolean isVerificationEnabled() {
                return enableVerification;
            }

            /**
             * @param value 新窗类型
             * @return 修改后的副本
             */
            public Options withWindowType(final Window.Type value) {
                return new Options(value, kaiserBeta, residualThreshold, enableVerification);
            }

            /**
             * @param value 新 β
             * @return 修改后的副本
             */
            public Options withKaiserBeta(final double value) {
                return new Options(windowType, value, residualThreshold, enableVerification);
            }

            /**
             * @param value 新阈值
             * @return 修改后的副本
             */
            public Options withResidualThreshold(final double value) {
                return new Options(windowType, kaiserBeta, value, enableVerification);
            }

            /**
             * @param value 是否开启验历
             * @return 修改后的副本
             */
            public Options withVerification(final boolean value) {
                return new Options(windowType, kaiserBeta, residualThreshold, value);
            }
        }

        /** 精估结果，对应 leap.cpp 的 {@code Spectral::Result}。 */
        public static final class Result {

            private final Waveform waveform;
            private final int analysisWindow;
            private final double residualRatio;
            private final boolean verified;

            private Result(final Waveform waveform,
                           final int analysisWindow,
                           final double residualRatio,
                           final boolean verified) {
                this.waveform = waveform;
                this.analysisWindow = analysisWindow;
                this.residualRatio = residualRatio;
                this.verified = verified;
            }

            /** @return 估计出的波形 */
            public Waveform getWaveform() {
                return waveform;
            }

            /** @return 实际使用的分析窗长 */
            public int getAnalysisWindow() {
                return analysisWindow;
            }

            /** @return 残差能量比 */
            public double getResidualRatio() {
                return residualRatio;
            }

            /** @return 残差比是否不超过阈值 */
            public boolean isVerified() {
                return verified;
            }
        }

        /**
         * 把原始样本抗混叠并重采样到分析采样率。
         *
         * <p>典型用法：104 Hz 的原始 EOG 先经本方法变成 20 Hz 的分析样本，再交给
         * {@link #estimate(double[], double, int, Options)}。内部使用
         * {@code com.feizhela.headband.signal.Sampler#resample}：先低通抑制
         * 目标 Nyquist 以上的能量，再做 Lanczos 插值重采样，因此不是简单分箱或抽取。
         *
         * <p>内部低通截止为 {@code 0.4 * min(源采样率, 目标采样率)}（104→20 Hz 时约
         * 8 Hz）。若后续特征需要保留接近 10 Hz 的频带，应提高分析采样率，或改用
         * 自定义截止/抽头的滤波与重采样。
         *
         * <p>注意：重采样会改变特征分布；如果模型原先在“未滤波 20 Hz 分箱”数据上
         * 训练，需要重训或证明兼容性。
         *
         * @param rawSignal           原始实样本
         * @param rawSamplingRate     原始采样率，Hz
         * @param analysisSamplingRate 分析采样率，Hz
         * @return 抗混叠并重采样后的样本
         */
        public static double[] prepare(final double[] rawSignal,
                                       final double rawSamplingRate,
                                       final double analysisSamplingRate) {
            Objects.requireNonNull(rawSignal, "rawSignal");
            if (rawSignal.length == 0) {
                throw new IllegalArgumentException("rawSignal must not be empty");
            }
            validateRate(rawSamplingRate, "rawSamplingRate");
            validateRate(analysisSamplingRate, "analysisSamplingRate");
            return resampler(rawSignal, rawSamplingRate)
                    .resample(rawSignal, analysisSamplingRate);
        }

        /**
         * 同 {@link #prepare(double[], double, double)}，但显式指定抗混叠滤波器的
         * 抽头数。
         *
         * @param rawSignal           原始实样本
         * @param rawSamplingRate     原始采样率，Hz
         * @param analysisSamplingRate 分析采样率，Hz
         * @param taps                抗混叠滤波器抽头数
         * @return 抗混叠并重采样后的样本
         */
        public static double[] prepare(final double[] rawSignal,
                                       final double rawSamplingRate,
                                       final double analysisSamplingRate,
                                       final int taps) {
            Objects.requireNonNull(rawSignal, "rawSignal");
            if (rawSignal.length == 0) {
                throw new IllegalArgumentException("rawSignal must not be empty");
            }
            validateRate(rawSamplingRate, "rawSamplingRate");
            validateRate(analysisSamplingRate, "analysisSamplingRate");
            return resampler(rawSignal, rawSamplingRate)
                    .resample(rawSignal, analysisSamplingRate, taps);
        }

        /**
         * 直接从原始采样率的信号做历法式频谱精估：先 {@link #prepare} 抗混叠并
         * 重采样，再调用 {@link #estimate(double[], double, int)}。
         *
         * @param rawSignal           原始实样本
         * @param rawSamplingRate     原始采样率，Hz
         * @param analysisSamplingRate 分析采样率，Hz
         * @param numberOfComponents  分量个数
         * @return 精估结果
         */
        public static Result estimateFromRaw(final double[] rawSignal,
                                             final double rawSamplingRate,
                                             final double analysisSamplingRate,
                                             final int numberOfComponents) {
            return estimateFromRaw(rawSignal, rawSamplingRate, analysisSamplingRate,
                    numberOfComponents, new Options());
        }

        /**
         * 直接从原始采样率的信号做历法式频谱精估：先 {@link #prepare} 抗混叠并
         * 重采样，再调用 {@link #estimate(double[], double, int, Options)}。
         *
         * @param rawSignal           原始实样本
         * @param rawSamplingRate     原始采样率，Hz
         * @param analysisSamplingRate 分析采样率，Hz
         * @param numberOfComponents  分量个数
         * @param options             选项
         * @return 精估结果
         */
        public static Result estimateFromRaw(final double[] rawSignal,
                                             final double rawSamplingRate,
                                             final double analysisSamplingRate,
                                             final int numberOfComponents,
                                             final Options options) {
            Objects.requireNonNull(options, "options");
            final double[] prepared = prepare(rawSignal, rawSamplingRate, analysisSamplingRate);
            return estimate(prepared, analysisSamplingRate, numberOfComponents, options);
        }

        private static void validateRate(final double rate, final String name) {
            if (!Double.isFinite(rate) || rate <= 0.0) {
                throw new IllegalArgumentException(name + " must be positive and finite");
            }
        }

        private static com.feizhela.headband.signal.Sampler resampler(
                final double[] rawSignal, final double rawSamplingRate) {
            final double duration = rawSignal.length / rawSamplingRate;
            if (!Double.isFinite(duration) || duration <= 0.0) {
                throw new IllegalArgumentException(
                        "rawSignal length and rawSamplingRate produce an invalid duration");
            }
            return new com.feizhela.headband.signal.Sampler(
                    new com.feizhela.headband.signal.Sample(duration, rawSamplingRate));
        }

        /**
         * @param signal             实信号
         * @param samplingRate       采样率
         * @param numberOfComponents 分量个数
         * @return 精估结果
         */
        public static Result estimate(final double[] signal,
                                      final double samplingRate,
                                      final int numberOfComponents) {
            return estimate(signal, samplingRate, numberOfComponents, new Options());
        }

        /**
         * 历法式频谱精估。
         *
         * @param signal             实信号
         * @param samplingRate       采样率
         * @param numberOfComponents 分量个数
         * @param options            选项
         * @return 精估结果
         */
        public static Result estimate(final double[] signal,
                                      final double samplingRate,
                                      final int numberOfComponents,
                                      final Options options) {
            Objects.requireNonNull(signal, "signal");
            Objects.requireNonNull(options, "options");
            if (!Double.isFinite(samplingRate) || samplingRate <= 0.0) {
                throw new IllegalArgumentException("samplingRate must be positive and finite");
            }
            if (numberOfComponents < 0) {
                throw new IllegalArgumentException("numberOfComponents must not be negative");
            }
            for (double value : signal) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("signal must contain only finite values");
                }
            }

            Result result = new Result(new Waveform(), 0, 1.0, false);
            final int length = signal.length;
            if (length < 8 || numberOfComponents == 0) {
                return result;
            }

            final Sampler sampler = new Sampler();

            // Step 0: 粗估
            final double[] hannWindow = Window.make(Window.Type.HANN, length);
            final Complex[] spectralData = new Complex[length];
            for (int index = 0; index < length; index++) {
                spectralData[index] = new Complex(signal[index] * hannWindow[index], 0.0);
            }
            sampler.setDirectData(spectralData);
            final Complex[] transformed = sampler.fastFourierTransform();
            final double[] magnitudes = new double[length / 2 + 1];
            for (int index = 0; index <= length / 2; index++) {
                magnitudes[index] = transformed[index].magnitude();
            }
            final int[] peakBins = findPeakBins(
                    magnitudes, numberOfComponents, Math.max(2, length / 100));
            if (peakBins.length == 0) {
                return result;
            }

            final double[] coarseFrequencies = new double[peakBins.length];
            final double[] coarseWeights = new double[peakBins.length];
            for (int binIndex = 0; binIndex < peakBins.length; binIndex++) {
                final double offset = Leak.interpolatePeakOffset(transformed, peakBins[binIndex]);
                coarseFrequencies[binIndex] = (peakBins[binIndex] + offset)
                        * samplingRate / length;
                coarseWeights[binIndex] = magnitudes[peakBins[binIndex]];
            }

            // Step 1: 调日选窗
            final int minimumWindow = Math.max(64, length / 2);
            final int analysisWindow = Leap.selectAnalysisWindow(
                    coarseFrequencies, coarseWeights, samplingRate, minimumWindow, length);
            result = new Result(new Waveform(), analysisWindow, 1.0, false);

            // Step 2: 闰余校正
            final double[] window = Window.make(
                    options.getWindowType(), analysisWindow, options.getKaiserBeta());
            final Complex[] windowedData = new Complex[analysisWindow];
            for (int index = 0; index < analysisWindow; index++) {
                windowedData[index] = new Complex(signal[index] * window[index], 0.0);
            }
            sampler.setDirectData(windowedData);
            final Complex[] windowedSpectrum = sampler.fastFourierTransform();
            final double[] windowedMagnitudes = new double[analysisWindow / 2 + 1];
            for (int index = 0; index <= analysisWindow / 2; index++) {
                windowedMagnitudes[index] = windowedSpectrum[index].magnitude();
            }
            final int[] windowedPeakBins = findPeakBins(
                    windowedMagnitudes, numberOfComponents, Math.max(2, analysisWindow / 100));
            if (windowedPeakBins.length == 0) {
                return result;
            }

            final double[] initialFrequencies = new double[windowedPeakBins.length];
            for (int binIndex = 0; binIndex < windowedPeakBins.length; binIndex++) {
                final double offset =
                        Leak.interpolatePeakOffset(windowedSpectrum, windowedPeakBins[binIndex]);
                initialFrequencies[binIndex] = (windowedPeakBins[binIndex] + offset)
                        * samplingRate / analysisWindow;
            }

            final int[] order = new int[windowedPeakBins.length];
            for (int index = 0; index < order.length; index++) {
                order[index] = index;
            }
            for (int first = 0; first < order.length; first++) {
                int best = first;
                for (int second = first + 1; second < order.length; second++) {
                    if (windowedMagnitudes[windowedPeakBins[order[second]]]
                            > windowedMagnitudes[windowedPeakBins[order[best]]]) {
                        best = second;
                    }
                }
                if (best != first) {
                    final int swap = order[first];
                    order[first] = order[best];
                    order[best] = swap;
                }
            }

            // Step 3: 定朔精修
            final double[] remainingSignal = signal.clone();
            final Waveform waveform = new Waveform();
            final double halfWidth = 0.5 * samplingRate / analysisWindow;
            double maximumAmplitude = 0.0;

            for (int orderIndex : order) {
                if (waveform.size() >= numberOfComponents) {
                    break;
                }
                final double centerFrequency = initialFrequencies[orderIndex];
                final double lowerFrequency = Math.max(1e-9, centerFrequency - halfWidth);
                final double upperFrequency =
                        Math.min(samplingRate * 0.5 - 1e-9, centerFrequency + halfWidth);
                if (upperFrequency <= lowerFrequency) {
                    continue;
                }
                final DoubleUnaryOperator objective = trialFrequency ->
                        correlationMagnitude(remainingSignal, samplingRate, trialFrequency);
                final double refinedFrequency = goldenSectionMaximum(
                        objective, lowerFrequency, upperFrequency, 50);
                final Epicycle parameters = extractParameters(
                        remainingSignal, samplingRate, refinedFrequency, length);
                if (waveform.size() == 0) {
                    maximumAmplitude = parameters.amplitude;
                }
                if (parameters.amplitude < 1e-4 * Math.max(maximumAmplitude, 1e-12)) {
                    continue;
                }
                waveform.add(parameters);
                for (int index = 0; index < length; index++) {
                    final double time = (double) index / samplingRate;
                    final double value = parameters.amplitude
                            * Math.cos(parameters.omega * time + parameters.phase);
                    remainingSignal[index] -= value;
                }
            }

            // Step 4: 验历闭环
            final double ratio = residualRatio(signal, samplingRate, waveform);
            result = new Result(waveform, analysisWindow, ratio,
                    ratio <= options.getResidualThreshold());

            if (!result.isVerified() && options.isVerificationEnabled()) {
                final Options updated = options
                        .withKaiserBeta(Math.min(14.0, options.getKaiserBeta() + 3.0))
                        .withVerification(false);
                final Result retry = estimate(
                        signal, samplingRate, numberOfComponents + 1, updated);
                if (retry.getResidualRatio() < result.getResidualRatio()) {
                    final boolean verified =
                            retry.getResidualRatio() <= options.getResidualThreshold();
                    return new Result(retry.getWaveform(), retry.getAnalysisWindow(),
                            retry.getResidualRatio(), verified);
                }
            }

            return result;
        }

        static int[] findPeakBins(final double[] magnitudes,
                                  final int numberOfPeaks,
                                  final int minimumSeparation) {
            if (magnitudes.length < 3 || numberOfPeaks <= 0) {
                return new int[0];
            }
            final List<Integer> candidates = new ArrayList<>();
            for (int index = 1; index + 1 < magnitudes.length; index++) {
                if (magnitudes[index] > magnitudes[index - 1]
                        && magnitudes[index] > magnitudes[index + 1]) {
                    candidates.add(index);
                }
            }
            candidates.sort((first, second) -> {
                final int comparison = Double.compare(magnitudes[second], magnitudes[first]);
                return comparison != 0 ? comparison : Integer.compare(first, second);
            });
            final List<Integer> selected = new ArrayList<>(numberOfPeaks);
            for (int candidate : candidates) {
                boolean acceptable = true;
                for (int selectedBin : selected) {
                    if (Math.abs(candidate - selectedBin) < minimumSeparation) {
                        acceptable = false;
                        break;
                    }
                }
                if (acceptable) {
                    selected.add(candidate);
                    if (selected.size() == numberOfPeaks) {
                        break;
                    }
                }
            }
            final int[] output = new int[selected.size()];
            for (int index = 0; index < output.length; index++) {
                output[index] = selected.get(index);
            }
            return output;
        }

        private static double correlationMagnitude(final double[] signal,
                                                   final double samplingRate,
                                                   final double frequency) {
            final double angleStep = -2.0 * PI * frequency / samplingRate;
            final Complex step = new Complex(Math.cos(angleStep), Math.sin(angleStep));
            Complex rotation = new Complex(1.0, 0.0);
            double real = 0.0;
            double imaginary = 0.0;
            for (double value : signal) {
                real += value * rotation.getReal();
                imaginary += value * rotation.getImaginary();
                rotation = rotation.multiply(step);
            }
            return Math.hypot(real, imaginary);
        }

        private static Epicycle extractParameters(final double[] signal,
                                                  final double samplingRate,
                                                  final double frequency,
                                                  final double windowSum) {
            final double angleStep = -2.0 * PI * frequency / samplingRate;
            final Complex step = new Complex(Math.cos(angleStep), Math.sin(angleStep));
            Complex rotation = new Complex(1.0, 0.0);
            double real = 0.0;
            double imaginary = 0.0;
            for (double value : signal) {
                real += value * rotation.getReal();
                imaginary += value * rotation.getImaginary();
                rotation = rotation.multiply(step);
            }
            final double amplitude =
                    2.0 * Math.hypot(real, imaginary) / Math.max(1e-300, windowSum);
            final double phase = Math.atan2(imaginary, real);
            final double omega = 2.0 * PI * frequency;
            return new Epicycle(amplitude, omega, phase);
        }

        private static double goldenSectionMaximum(final DoubleUnaryOperator objective,
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
                if (firstValue > secondValue) {
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

        private static double residualRatio(final double[] signal,
                                            final double samplingRate,
                                            final Waveform waveform) {
            double signalEnergy = 0.0;
            double residualEnergy = 0.0;
            for (int index = 0; index < signal.length; index++) {
                final double time = (double) index / samplingRate;
                final double reconstruction = waveform.evaluate(time).getReal();
                final double residual = signal[index] - reconstruction;
                signalEnergy += signal[index] * signal[index];
                residualEnergy += residual * residual;
            }
            return residualEnergy / (signalEnergy + 1e-300);
        }
    }

    // =================================================================
    // 演示
    // =================================================================

    /**
     * 演示：1000 Hz 采 2 秒，三个已知分量加 0.001 的高斯噪声；先看未校正的 FFT
     * 峰值，再运行历法式频谱精估。
     *
     * @param args 未使用
     */
    public static void main(final String[] args) {
        final double samplingRate = 1000.0;
        final int length = 2000;

        final Waveform truth = new Waveform(
                new Epicycle(1.00, 2.0 * PI * 123.4, 0.30),
                new Epicycle(0.55, 2.0 * PI * 251.7, 1.10),
                new Epicycle(0.30, 2.0 * PI * 302.3, -0.70));

        final Sample segment = new Sample(2.0, 2000.0);
        final Sampler sampler = new Sampler(truth, segment);
        final double[] rawSignal = sampler.gatherReal();

        final Random random = new Random(42L);
        for (int index = 0; index < rawSignal.length; index++) {
            rawSignal[index] += random.nextGaussian() * 0.001;
        }

        System.out.printf(Locale.ROOT, "=== 采样计划 ===%n");
        System.out.printf(Locale.ROOT, "  segments       = %d%n", sampler.segmentCount());
        System.out.printf(Locale.ROOT, "  total points   = %d%n", sampler.totalPointCount());
        System.out.printf(Locale.ROOT, "  total duration = %.4f s%n", sampler.totalDuration());
        System.out.printf(Locale.ROOT, "%n=== 前 8 个采样点 ===%n");
        final double[] times = sampler.sampleTimes();
        for (int index = 0; index < 8 && index < times.length; index++) {
            System.out.printf(Locale.ROOT, "  t=%9.4f  x=%9.4f%n",
                    times[index], rawSignal[index]);
        }

        final Complex[] rawSpectrumData = new Complex[rawSignal.length];
        for (int index = 0; index < rawSignal.length; index++) {
            rawSpectrumData[index] = new Complex(rawSignal[index], 0.0);
        }
        final Sampler analysisSampler = new Sampler();
        analysisSampler.setDirectData(rawSpectrumData);
        final Complex[] spectrum = analysisSampler.fastFourierTransform();
        final double[] magnitudes = new double[rawSignal.length / 2 + 1];
        for (int index = 0; index <= rawSignal.length / 2; index++) {
            magnitudes[index] = spectrum[index].magnitude();
        }
        final int[] peakBins = Spectral.findPeakBins(
                magnitudes, truth.size(), Math.max(2, rawSignal.length / 100));
        System.out.printf(Locale.ROOT, "%n=== 普通 FFT 峰值（无校正）===\n");
        for (int bin : peakBins) {
            System.out.printf(Locale.ROOT, "  frequency=%9.4f  (bin %d)%n",
                    bin * samplingRate / rawSignal.length, bin);
        }

        final Spectral.Options options = new Spectral.Options()
                .withWindowType(Window.Type.KAISER)
                .withKaiserBeta(8.0)
                .withResidualThreshold(1e-4)
                .withVerification(true);
        final Spectral.Result result = Spectral.estimate(
                rawSignal, samplingRate, truth.size(), options);

        System.out.printf(Locale.ROOT, "%n=== 历法式频谱精估 ===%n");
        System.out.printf(Locale.ROOT, "  analysisWindow = %d%n", result.getAnalysisWindow());
        System.out.printf(Locale.ROOT, "  residualRatio  = %.6f%n", result.getResidualRatio());
        System.out.printf(Locale.ROOT, "  verified       = %s%n",
                result.isVerified() ? "yes" : "no");
        final List<Epicycle> epicycles = result.getWaveform().getEpicycles();
        for (int index = 0; index < epicycles.size(); index++) {
            final Epicycle epicycle = epicycles.get(index);
            final double frequency = epicycle.omega / (2.0 * PI);
            final double phase = Math.atan2(Math.sin(epicycle.phase), Math.cos(epicycle.phase));
            System.out.printf(Locale.ROOT,
                    "  #%d  frequency=%9.4f  amplitude=%7.4f  phase=%9.4f%n",
                    index + 1, frequency, epicycle.amplitude, phase);
        }
    }
}
