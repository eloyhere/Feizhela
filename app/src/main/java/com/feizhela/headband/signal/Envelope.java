package com.feizhela.headband.signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 幅度包络：时间轴上的分段线性折线。
 *
 * <p>包络由若干断点 {@code (time, level)} 定义，相邻断点之间线性插值。允许同一时刻
 * 出现多个断点，用于表示瞬时跳变（例如零时长的 attack、decay 或 release）：
 * <ul>
 *   <li>进入该时刻前的插值使用该时刻的<b>第一个</b>值；</li>
 *   <li>该时刻本身及其之后使用<b>最后一个</b>值。</li>
 * </ul>
 * 因此 {@code (1.0, 1.0), (1.0, 0.5)} 表示在 {@code t = 1.0} 处从 1 瞬时跳到 0.5。
 *
 * <p>本类提供三类用法：
 * <ol>
 *   <li>直接用断点构造任意包络；</li>
 *   <li>{@link #adsr} 构造常见的 ADSR 包络；</li>
 *   <li>{@link #fromSignal} 用解析信号（Hilbert 变换）提取实信号的幅度包络。</li>
 * </ol>
 *
 * <p>包络值是非负的幅度系数；{@link #apply} 把它乘到样本或纯音上。时间从 0 开始，
 * 断点时刻必须非负且非递减；{@link #duration()} 返回最后一个断点的时刻。
 */
public final class Envelope {

    private final double[] times;
    private final double[] incoming;
    private final double[] outgoing;

    /**
     * 由断点构造包络。时刻必须非递减且非负，幅度必须非负且有限；同一时刻的多个
     * 断点会被合并成一次跳变（保留第一个值作为进入值、最后一个值作为离开值）。
     *
     * @param times  断点时刻，秒
     * @param values 断点幅度
     */
    public Envelope(final double[] times, final double[] values) {
        Objects.requireNonNull(times, "times");
        Objects.requireNonNull(values, "values");
        if (times.length == 0 || times.length != values.length) {
            throw new IllegalArgumentException("times and values must be non-empty and equal length");
        }
        for (int index = 0; index < times.length; index++) {
            final double time = times[index];
            final double value = values[index];
            if (!Double.isFinite(time) || time < 0.0) {
                throw new IllegalArgumentException("times must be non-negative and finite");
            }
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("values must be non-negative and finite");
            }
            if (index > 0 && time < times[index - 1]) {
                throw new IllegalArgumentException("times must be non-decreasing");
            }
        }
        final List<Double> distinctTimes = new ArrayList<>();
        final List<Double> incomingValues = new ArrayList<>();
        final List<Double> outgoingValues = new ArrayList<>();
        int index = 0;
        while (index < times.length) {
            final double time = times[index];
            double firstValue = values[index];
            double lastValue = firstValue;
            int next = index + 1;
            while (next < times.length && times[next] == time) {
                lastValue = values[next];
                next++;
            }
            distinctTimes.add(time);
            incomingValues.add(firstValue);
            outgoingValues.add(lastValue);
            index = next;
        }
        this.times = toArray(distinctTimes);
        this.incoming = toArray(incomingValues);
        this.outgoing = toArray(outgoingValues);
    }

    private Envelope(final double[] times,
                     final double[] incoming,
                     final double[] outgoing) {
        this.times = times;
        this.incoming = incoming;
        this.outgoing = outgoing;
    }

    /**
     * ADSR 包络：线性 attack 到 1，线性 decay 到 sustain，保持到 gate 结束，再线性
     * release 到 0。
     *
     * <p>要求 {@code gateDuration >= attack + decay}；时长为 0 的阶段会被折叠为瞬时
     * 跳变。包络在最后一个断点之后保持为 0。
     *
     * @param attack        attack 时长，秒
     * @param decay         decay 时长，秒
     * @param sustainLevel  sustain 幅度，范围 [0, 1]
     * @param release       release 时长，秒
     * @param gateDuration  按键/闸门总时长，秒
     * @return ADSR 包络
     */
    public static Envelope adsr(final double attack,
                                final double decay,
                                final double sustainLevel,
                                final double release,
                                final double gateDuration) {
        if (!Double.isFinite(attack) || attack < 0.0) {
            throw new IllegalArgumentException("attack must be non-negative and finite");
        }
        if (!Double.isFinite(decay) || decay < 0.0) {
            throw new IllegalArgumentException("decay must be non-negative and finite");
        }
        if (!Double.isFinite(sustainLevel) || sustainLevel < 0.0 || sustainLevel > 1.0) {
            throw new IllegalArgumentException("sustainLevel must be in [0, 1]");
        }
        if (!Double.isFinite(release) || release < 0.0) {
            throw new IllegalArgumentException("release must be non-negative and finite");
        }
        if (!Double.isFinite(gateDuration) || gateDuration < 0.0) {
            throw new IllegalArgumentException("gateDuration must be non-negative and finite");
        }
        if (gateDuration < attack + decay) {
            throw new IllegalArgumentException("gateDuration must be at least attack + decay");
        }
        return new Envelope(
                new double[]{0.0, attack, attack + decay, gateDuration, gateDuration + release},
                new double[]{0.0, 1.0, sustainLevel, sustainLevel, 0.0});
    }

    /**
     * 用解析信号（Hilbert 变换）提取实信号的幅度包络。
     *
     * <p>先去掉信号均值，再通过 FFT 构造解析信号：正频率加倍、负频率置零，然后做
     * 逆变换取模。返回的包络在 {@code 0, 1 / samplingRate, ...} 处有断点。
     *
     * @param signal       实信号
     * @param samplingRate 采样率
     * @return 幅度包络
     */
    public static Envelope fromSignal(final double[] signal, final double samplingRate) {
        Objects.requireNonNull(signal, "signal");
        if (signal.length < 2) {
            throw new IllegalArgumentException("at least 2 samples are required");
        }
        validateSamplingRate(samplingRate);
        for (double value : signal) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("signal must contain only finite values");
            }
        }

        double mean = 0.0;
        for (double value : signal) {
            mean += value;
        }
        mean /= signal.length;

        final int length = signal.length;
        final Complex[] data = new Complex[length];
        for (int index = 0; index < length; index++) {
            data[index] = new Complex(signal[index] - mean, 0.0);
        }
        final Complex[] spectrum = Sampler.forwardArray(data);
        for (int index = 1; index <= (length - 1) / 2; index++) {
            spectrum[index] = spectrum[index].multiply(2.0);
        }
        for (int index = length / 2 + 1; index < length; index++) {
            spectrum[index] = new Complex();
        }
        final Complex[] analytic = Sampler.inverseArray(spectrum);

        final double[] times = new double[length];
        final double[] values = new double[length];
        for (int index = 0; index < length; index++) {
            times[index] = index / samplingRate;
            values[index] = analytic[index].magnitude();
        }
        return new Envelope(times, values);
    }

    /** @return 断点（不同时刻）个数 */
    public int size() {
        return times.length;
    }

    /** @return 包络时长，即最后一个断点的时刻 */
    public double duration() {
        return times[times.length - 1];
    }

    /** @return 断点时刻的拷贝 */
    public double[] getTimes() {
        return times.clone();
    }

    /** @return 每个断点进入时刻前的幅度拷贝 */
    public double[] getIncomingValues() {
        return incoming.clone();
    }

    /** @return 每个断点时刻及其后的幅度拷贝 */
    public double[] getOutgoingValues() {
        return outgoing.clone();
    }

    /**
     * 在指定时刻求包络值。
     *
     * @param time 时间，秒
     * @return 包络幅度；第一个断点之前取进入值，最后一个断点之后取离开值
     */
    public double evaluate(final double time) {
        if (!Double.isFinite(time)) {
            throw new IllegalArgumentException("time must be finite");
        }
        final int last = times.length - 1;
        if (time <= times[0]) {
            return time == times[0] ? outgoing[0] : incoming[0];
        }
        if (time >= times[last]) {
            return outgoing[last];
        }
        int low = 0;
        int high = last;
        while (low + 1 < high) {
            final int middle = (low + high) >>> 1;
            if (times[middle] <= time) {
                low = middle;
            } else {
                high = middle;
            }
        }
        if (time == times[low]) {
            return outgoing[low];
        }
        if (time == times[high]) {
            return outgoing[high];
        }
        final double ratio = (time - times[low]) / (times[high] - times[low]);
        return outgoing[low] + ratio * (incoming[high] - outgoing[low]);
    }

    /**
     * 按采样率生成包络样本。
     *
     * @param samplingRate 采样率
     * @param count        样本数
     * @return 包络样本
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
     * 把包络乘到样本上（逐点）。
     *
     * @param samples      输入样本
     * @param samplingRate 采样率
     * @return 乘过包络的样本
     */
    public double[] apply(final double[] samples, final double samplingRate) {
        Objects.requireNonNull(samples, "samples");
        validateSamplingRate(samplingRate);
        final double[] output = new double[samples.length];
        for (int index = 0; index < samples.length; index++) {
            output[index] = samples[index] * evaluate(index / samplingRate);
        }
        return output;
    }

    /**
     * 生成纯音并经包络整形。
     *
     * @param tone         纯音
     * @param samplingRate 采样率
     * @param count        样本数
     * @return 整形后的样本
     */
    public double[] apply(final Tone tone, final double samplingRate, final int count) {
        Objects.requireNonNull(tone, "tone");
        return apply(tone.sample(samplingRate, count), samplingRate);
    }

    /** @return 最大幅度 */
    public double peak() {
        double maximum = 0.0;
        for (int index = 0; index < times.length; index++) {
            maximum = Math.max(maximum, Math.max(incoming[index], outgoing[index]));
        }
        return maximum;
    }

    /**
     * @param factor 非负比例
     * @return 按比例缩放后的包络
     */
    public Envelope scale(final double factor) {
        if (!Double.isFinite(factor) || factor < 0.0) {
            throw new IllegalArgumentException("factor must be non-negative and finite");
        }
        final double[] scaledIncoming = new double[incoming.length];
        final double[] scaledOutgoing = new double[outgoing.length];
        for (int index = 0; index < incoming.length; index++) {
            scaledIncoming[index] = incoming[index] * factor;
            scaledOutgoing[index] = outgoing[index] * factor;
        }
        return new Envelope(times.clone(), scaledIncoming, scaledOutgoing);
    }

    /**
     * @return 峰值归一化到 1 的包络
     */
    public Envelope normalize() {
        final double peak = peak();
        if (peak == 0.0) {
            throw new ArithmeticException("cannot normalize a silent envelope");
        }
        return scale(1.0 / peak);
    }

    private static double[] toArray(final List<Double> values) {
        final double[] output = new double[values.size()];
        for (int index = 0; index < output.length; index++) {
            output[index] = values.get(index);
        }
        return output;
    }

    private static void validateSamplingRate(final double samplingRate) {
        if (!Double.isFinite(samplingRate) || samplingRate <= 0.0) {
            throw new IllegalArgumentException("samplingRate must be positive and finite");
        }
    }
}
