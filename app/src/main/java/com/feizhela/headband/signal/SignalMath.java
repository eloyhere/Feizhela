package com.feizhela.headband.signal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 仅供信号算法使用的校验和缓冲区工具。 */
final class SignalMath {
    private SignalMath() {}

    static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    static void finite(Complex value, String name) {
        Objects.requireNonNull(value, name);
        finite(value.getReal(), name + ".real");
        finite(value.getImaginary(), name + ".imaginary");
    }

    static void validate(Complex[] values) {
        Objects.requireNonNull(values, "values");
        for (Complex value : values) finite(value, "sample");
    }

    static Complex[] copy(Complex[] values) {
        validate(values);
        return values.clone(); // Complex 是不可变值对象。
    }

    static Complex[] array(List<Complex> values) {
        Objects.requireNonNull(values, "values");
        return copy(values.toArray(new Complex[0]));
    }

    static List<Complex> list(Complex[] values) {
        return Collections.unmodifiableList(Arrays.asList(copy(values)));
    }

    static Complex exp(double angle) {
        return new Complex(Math.cos(angle), Math.sin(angle));
    }

    static Complex[] zeros(int count) {
        Complex[] result = new Complex[count];
        Arrays.fill(result, new Complex());
        return result;
    }

    static int nextPowerOfTwo(int n) {
        if (n < 1 || n > (1 << 30)) throw new IllegalArgumentException("unsupported transform length");
        if (n == 1) return 1;
        return Integer.highestOneBit(n - 1) << 1;
    }

    static boolean isPower(int n, int radix) {
        if (n < 1 || radix < 2) return false;
        while (n % radix == 0) n /= radix;
        return n == 1;
    }
}
