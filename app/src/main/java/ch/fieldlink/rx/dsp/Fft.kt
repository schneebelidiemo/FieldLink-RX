package ch.fieldlink.rx.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Fft {
    fun transform(real: DoubleArray, imaginary: DoubleArray) {
        require(real.size == imaginary.size && real.size > 0 && real.size.countOneBits() == 1) {
            "FFT input length must be a power of two."
        }
        val size = real.size

        var target = 0
        for (source in 1 until size) {
            var bit = size shr 1
            while ((target and bit) != 0) {
                target = target xor bit
                bit = bit shr 1
            }
            target = target xor bit
            if (source < target) {
                val realValue = real[source]
                real[source] = real[target]
                real[target] = realValue
                val imaginaryValue = imaginary[source]
                imaginary[source] = imaginary[target]
                imaginary[target] = imaginaryValue
            }
        }

        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val rootReal = cos(angle)
            val rootImaginary = sin(angle)
            val half = length / 2
            var block = 0
            while (block < size) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (offset in 0 until half) {
                    val even = block + offset
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary

                    val nextReal = twiddleReal * rootReal - twiddleImaginary * rootImaginary
                    twiddleImaginary = twiddleReal * rootImaginary + twiddleImaginary * rootReal
                    twiddleReal = nextReal
                }
                block += length
            }
            length = length shl 1
        }
    }
}

