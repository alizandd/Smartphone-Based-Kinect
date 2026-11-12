package dev.motionhub.core

import kotlin.math.acos
import kotlin.math.sqrt

/** Small immutable 3-vector. Allocation-heavy by design: Phase 0 favours clarity over GC tuning. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(this dot this)
    fun normalized(): Vec3 {
        val n = norm()
        return if (n < 1e-12) this else this * (1.0 / n)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
    }
}

fun midpoint(a: Vec3, b: Vec3) = (a + b) * 0.5

/** Angle at [b] formed by a-b-c, in degrees. */
fun angleDeg(a: Vec3, b: Vec3, c: Vec3): Double {
    val u = (a - b).normalized()
    val v = (c - b).normalized()
    return Math.toDegrees(acos((u dot v).coerceIn(-1.0, 1.0)))
}

fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val s = values.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
}

/**
 * One Euro filter (Casiez et al. 2012): low lag on fast motion, strong smoothing when slow.
 * Time in seconds.
 */
class OneEuroFilter(
    var minCutoff: Double = 1.0,
    var beta: Double = 0.05,
    var dCutoff: Double = 1.0,
) {
    private var xPrev = Double.NaN
    private var dxPrev = 0.0
    private var tPrev = Double.NaN

    fun reset() {
        xPrev = Double.NaN; dxPrev = 0.0; tPrev = Double.NaN
    }

    fun filter(x: Double, tSec: Double): Double {
        if (xPrev.isNaN() || tPrev.isNaN() || tSec <= tPrev) {
            xPrev = x; tPrev = tSec; return x
        }
        val dt = tSec - tPrev
        val dx = (x - xPrev) / dt
        val aD = alpha(dt, dCutoff)
        val dxHat = aD * dx + (1 - aD) * dxPrev
        val cutoff = minCutoff + beta * kotlin.math.abs(dxHat)
        val a = alpha(dt, cutoff)
        val xHat = a * x + (1 - a) * xPrev
        xPrev = xHat; dxPrev = dxHat; tPrev = tSec
        return xHat
    }

    private fun alpha(dt: Double, cutoff: Double): Double {
        val tau = 1.0 / (2 * Math.PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
