package com.feizhela.headband.signal;

import java.util.Objects;

/**
 * 频率量：数值加单位，与 {@link Angle} 的处理方式一致。
 *
 * <p>支持两种单位：
 * <ul>
 *   <li>{@link Unit#HERTZ}：每秒周期数（Hz）；</li>
 *   <li>{@link Unit#RADIAN_PER_SECOND}：弧度每秒（rad/s）。</li>
 * </ul>
 *
 * <p>与角度不同，频率不是周期量，因此数值不会被取模。允许负频率（表示反向旋转或
 * 复数谱中的负频率分量），也允许零频率；零频率没有周期，{@link #period()} 会抛出
 * {@link ArithmeticException}。
 *
 * <p>所有运算都以左操作数的单位返回结果；跨单位运算会先换算。
 */
public final class Frequency {

    /** 频率单位及其到赫兹的换算系数。 */
    public enum Unit {
        /** 赫兹：每秒周期数。 */
        HERTZ(1.0),
        /** 弧度每秒。 */
        RADIAN_PER_SECOND(1.0 / (2.0 * Math.PI));

        private final double hertzPerUnit;

        Unit(final double hertzPerUnit) {
            this.hertzPerUnit = hertzPerUnit;
        }

        double toHertz(final double value) {
            return value * hertzPerUnit;
        }
    }

    private final double value;
    private final Unit unit;

    /**
     * 以赫兹构造频率。
     *
     * @param value 赫兹值
     */
    public Frequency(final double value) {
        this(value, Unit.HERTZ);
    }

    /**
     * @param value 数值
     * @param unit  单位
     */
    public Frequency(final double value, final Unit unit) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        this.unit = Objects.requireNonNull(unit, "unit");
        this.value = value;
    }

    /**
     * @param value 赫兹值
     * @return 频率
     */
    public static Frequency fromHertz(final double value) {
        return new Frequency(value, Unit.HERTZ);
    }

    /**
     * @param value 每秒周期数
     * @return 频率
     */
    public static Frequency fromCyclesPerSecond(final double value) {
        return fromHertz(value);
    }

    /**
     * @param value 弧度每秒
     * @return 频率
     */
    public static Frequency fromRadianPerSecond(final double value) {
        return new Frequency(value, Unit.RADIAN_PER_SECOND);
    }

    /**
     * 由周期构造频率（返回赫兹单位）。
     *
     * @param seconds 周期，秒；必须为正
     * @return 频率
     */
    public static Frequency fromPeriod(final double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            throw new IllegalArgumentException("period must be positive and finite");
        }
        return fromHertz(1.0 / seconds);
    }

    /** @return 数值 */
    public double getValue() {
        return value;
    }

    /** @return 单位 */
    public Unit getUnit() {
        return unit;
    }

    /** @return 单位是否为赫兹 */
    public boolean isHertz() {
        return unit == Unit.HERTZ;
    }

    /** @return 单位是否为弧度每秒 */
    public boolean isRadianPerSecond() {
        return unit == Unit.RADIAN_PER_SECOND;
    }

    /** @return 换算成赫兹单位的频率 */
    public Frequency toHertz() {
        return toUnit(Unit.HERTZ);
    }

    /** @return 换算成弧度每秒单位的频率 */
    public Frequency toRadianPerSecond() {
        return toUnit(Unit.RADIAN_PER_SECOND);
    }

    /**
     * @param targetUnit 目标单位
     * @return 换算后的频率；单位相同时返回自身
     */
    public Frequency toUnit(final Unit targetUnit) {
        Objects.requireNonNull(targetUnit, "targetUnit");
        if (targetUnit == unit) {
            return this;
        }
        final double scale = unit.hertzPerUnit / targetUnit.hertzPerUnit;
        return new Frequency(value * scale, targetUnit);
    }

    /** @return 赫兹值 */
    public double hertz() {
        return unit.toHertz(value);
    }

    /** @return 每秒周期数，与 {@link #hertz()} 相同 */
    public double cyclesPerSecond() {
        return hertz();
    }

    /** @return 弧度每秒 */
    public double radianPerSecond() {
        return unit.toHertz(value) * 2.0 * Math.PI;
    }

    /** @return 角频率，与 {@link #radianPerSecond()} 相同 */
    public double angularFrequency() {
        return radianPerSecond();
    }

    /**
     * @return 周期，秒；取赫兹绝对值的倒数
     */
    public double period() {
        final double hertz = hertz();
        if (hertz == 0.0) {
            throw new ArithmeticException("zero frequency has no period");
        }
        return 1.0 / Math.abs(hertz);
    }

    /**
     * @param other 另一个频率
     * @return 以当前单位表示的和
     */
    public Frequency plus(final Frequency other) {
        Objects.requireNonNull(other, "other");
        return new Frequency(value + other.toUnit(unit).value, unit);
    }

    /**
     * @param other 另一个频率
     * @return 以当前单位表示的差
     */
    public Frequency minus(final Frequency other) {
        Objects.requireNonNull(other, "other");
        return new Frequency(value - other.toUnit(unit).value, unit);
    }

    /**
     * @param scalar 标量
     * @return 以当前单位表示的积
     */
    public Frequency multiply(final double scalar) {
        if (!Double.isFinite(scalar)) {
            throw new IllegalArgumentException("scalar must be finite");
        }
        return new Frequency(value * scalar, unit);
    }

    /**
     * @param scalar 非零标量
     * @return 以当前单位表示的商
     */
    public Frequency divide(final double scalar) {
        if (scalar == 0.0) {
            throw new ArithmeticException("Divide by zero");
        }
        if (!Double.isFinite(scalar)) {
            throw new IllegalArgumentException("scalar must be finite");
        }
        return new Frequency(value / scalar, unit);
    }

    /**
     * @param other 另一个频率
     * @return 两个频率的赫兹比值
     */
    public double ratio(final Frequency other) {
        Objects.requireNonNull(other, "other");
        final double otherHertz = other.hertz();
        if (otherHertz == 0.0) {
            throw new ArithmeticException("Divide by zero");
        }
        return hertz() / otherHertz;
    }

    /** @return 相反数频率 */
    public Frequency negate() {
        return new Frequency(-value, unit);
    }

    /** @return 绝对值频率 */
    public Frequency absolute() {
        return new Frequency(Math.abs(value), unit);
    }

    /** @return 是否为零频率 */
    public boolean isZero() {
        return value == 0.0;
    }

    /** @return 是否为正频率 */
    public boolean isPositive() {
        return value > 0.0;
    }

    /** @return 是否为负频率 */
    public boolean isNegative() {
        return value < 0.0;
    }

    /**
     * 把二阶带通谐振器作用在实样本上。
     *
     * <p>中心频率取本频率的绝对值，{@code q} 控制带宽（Q 越大越窄）；谐振器为因果
     * IIR，初始状态为零，输出与输入等长。中心频率必须位于
     * {@code (0, samplingRate / 2)}。
     *
     * @param samples      实样本
     * @param samplingRate 采样率，Hz
     * @param q            品质因数，必须为正
     * @return 谐振器输出
     */
    public double[] resonate(final double[] samples,
                             final double samplingRate,
                             final double q) {
        Objects.requireNonNull(samples, "samples");
        if (samples.length == 0) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        for (double sample : samples) {
            if (!Double.isFinite(sample)) {
                throw new IllegalArgumentException("samples must be finite");
            }
        }
        if (!Double.isFinite(samplingRate) || samplingRate <= 0.0) {
            throw new IllegalArgumentException("samplingRate must be positive and finite");
        }
        if (!Double.isFinite(q) || q <= 0.0) {
            throw new IllegalArgumentException("q must be positive and finite");
        }
        final double center = Math.abs(hertz());
        if (center <= 0.0 || center >= samplingRate * 0.5) {
            throw new IllegalArgumentException("resonance frequency must be in (0, Nyquist)");
        }
        final double omega = 2.0 * Math.PI * center / samplingRate;
        final double alpha = Math.sin(omega) / (2.0 * q);
        final double a0 = 1.0 + alpha;
        final double b0 = alpha / a0;
        final double b2 = -alpha / a0;
        final double a1 = -2.0 * Math.cos(omega) / a0;
        final double a2 = (1.0 - alpha) / a0;
        final double[] output = new double[samples.length];
        double previousInput1 = 0.0;
        double previousInput2 = 0.0;
        double previousOutput1 = 0.0;
        double previousOutput2 = 0.0;
        for (int index = 0; index < samples.length; index++) {
            final double input = samples[index];
            final double filtered = b0 * input + b2 * previousInput2
                    - a1 * previousOutput1 - a2 * previousOutput2;
            output[index] = filtered;
            previousInput2 = previousInput1;
            previousInput1 = input;
            previousOutput2 = previousOutput1;
            previousOutput1 = filtered;
        }
        return output;
    }
}
