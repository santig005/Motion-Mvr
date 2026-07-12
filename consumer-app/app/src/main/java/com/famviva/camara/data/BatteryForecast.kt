package com.famviva.camara.data

import kotlin.math.abs

/**
 * Scoring the battery-life forecast against reality.
 *
 * Each recorded [BatterySample] carries the NVR's live ETA at that moment ([BatterySample.etaMinutes],
 * minutes until ~5%). To score a past prediction we look at what actually happened *afterward*: over
 * the same off-charger streak we measure the realized discharge rate and extrapolate it to the same
 * 5% floor the NVR uses — giving an "actual" minutes-to-empty to compare the forecast against. The
 * point of keeping this history is exactly what the user asked for: to see how close the forecast was
 * and whether it improves over time.
 */

/** The 5% "effectively dead" floor both the NVR and the app-side estimator extrapolate to. */
private const val FORECAST_FLOOR_PCT = 5

/** A realized window needs at least this much time and drop to yield a trustworthy rate. */
private const val MIN_WINDOW_HOURS = 0.5
private const val MIN_DROP_PCT = 3

/** One verified prediction: what the NVR forecast at a past moment vs. what actually happened. */
data class ForecastEval(
    val predictedAtEpoch: Long,
    val batteryAtPrediction: Int,
    val predictedEtaMin: Int,
    val actualEtaMin: Int,
) {
    /** Signed error in minutes: positive = the forecast was too optimistic (it predicted more life
     *  than actually remained). */
    val errorMin: Int get() = predictedEtaMin - actualEtaMin
}

/** Aggregate forecast accuracy computed from the recorded history. */
data class ForecastAccuracy(
    val count: Int,
    val meanAbsErrorMin: Int,
    val medianAbsErrorMin: Int,
    /** Mean signed error: >0 the forecast runs optimistic, <0 pessimistic. */
    val meanSignedErrorMin: Int,
    /** true if recent predictions are more accurate than older ones, false if worse, null if there
     *  isn't enough history to tell yet. */
    val improving: Boolean?,
)

/**
 * Evaluates every past forecast (the ETA stored with each sample) against the discharge that actually
 * followed it within the same off-charger streak. Returns null if no prediction has yet accrued a big
 * enough realized window to be scored — the history is still being collected.
 */
fun evaluateBatteryForecast(samples: List<BatterySample>): ForecastAccuracy? {
    val evals = collectForecastEvals(samples)
    if (evals.isEmpty()) return null

    val absErrors = evals.map { abs(it.errorMin) }.sorted()
    val mean = absErrors.average().toInt()
    val median = absErrors[absErrors.size / 2]
    val signed = evals.map { it.errorMin }.average().toInt()

    // Trend: compare the older half against the recent half (needs a few points to be meaningful).
    val improving = if (evals.size >= 4) {
        val sorted = evals.sortedBy { it.predictedAtEpoch }
        val half = sorted.size / 2
        val older = sorted.take(half).map { abs(it.errorMin) }.average()
        val recent = sorted.takeLast(half).map { abs(it.errorMin) }.average()
        recent < older
    } else {
        null
    }
    return ForecastAccuracy(evals.size, mean, median, signed, improving)
}

private fun collectForecastEvals(samples: List<BatterySample>): List<ForecastEval> {
    val evals = mutableListOf<ForecastEval>()
    var i = 0
    while (i < samples.size) {
        if (samples[i].charging) { i++; continue }
        // Extend the contiguous discharging streak samples[i..j].
        var j = i
        while (j + 1 < samples.size && !samples[j + 1].charging) j++
        val end = samples[j]
        for (k in i..j) {
            val s = samples[k]
            val eta = s.etaMinutes ?: continue
            if (s.battery <= FORECAST_FLOOR_PCT) continue
            val dtHours = (end.epochSec - s.epochSec) / 3600.0
            val drop = s.battery - end.battery
            if (dtHours < MIN_WINDOW_HOURS || drop < MIN_DROP_PCT) continue
            val realizedRate = drop / dtHours                       // %/h actually observed after
            if (realizedRate <= 0) continue
            val actualEta = ((s.battery - FORECAST_FLOOR_PCT) / realizedRate * 60.0).toInt()
            evals += ForecastEval(s.epochSec, s.battery, eta, actualEta)
        }
        i = j + 1
    }
    return evals
}
