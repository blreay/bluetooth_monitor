package com.blreay.bluetoothmonitor

/**
 * Turns a scan that found nothing into an explanation of *why*.
 *
 * This exists because "nothing happened" used to be the app's entire output on
 * every failure path. Six distinct outcomes - no radio, Bluetooth off, missing
 * permission, band not seen, toggle refused, scan error - all rendered
 * identically as silence, because [Notifier.outcome] is gated behind the debug
 * extra. A user tapping the icon could not tell a working app from a broken
 * one, and neither could the developer.
 *
 * The facts that discriminate the causes are cheap to collect during the scan
 * and impossible to reconstruct afterwards, so they are captured here.
 *
 * Pure and free of Android types so the ordering rules can be unit tested on
 * the JVM. Same split as [ReEnablePolicy]: the decision is a pure function,
 * the platform calls it and acts on the result.
 */
object RunReport {

    /**
     * What one fast phase actually observed.
     *
     * @param windowMs the scan window that was requested
     * @param elapsedMs how long the scan actually ran before it was stopped.
     *        Much less than [windowMs] means something killed the process
     *        mid-scan, which makes every other count meaningless.
     * @param devicesSeen distinct BLE addresses observed. This is the single
     *        most diagnostic number: zero means the scan was starved, a
     *        healthy number means the scan worked and the band simply was not
     *        advertising.
     * @param namedSeen how many of those carried a name in the advertisement.
     *        Informational only - plenty of devices advertise no name at all,
     *        so a low count is normal and never indicates a broken scan.
     * @param nearMisses devices that looked band-shaped (a Xiaomi/Band name, or
     *        the target MAC suffix) but did not satisfy [BandMatcher.isTarget].
     *        Non-zero means the band probably *was* advertising under a name
     *        this build does not recognise.
     * @param targetBonded whether the target is in the paired-device list. This
     *        is where a user "sees" the band in system settings, and a bonded
     *        band that is actively connected to Mi Fitness has stopped
     *        advertising - so bonded plus not-seen is the expected combination,
     *        not a bug.
     * @param scanFailedCode the `onScanFailed` code, or null if the scan never
     *        failed. Note that Android's 5-starts-per-30s throttle does NOT
     *        arrive here: the 6th startScan is accepted and then silently
     *        starved of results, so throttling shows up as a full window with
     *        [devicesSeen] at zero.
     */
    data class ScanFacts(
        val windowMs: Long,
        val elapsedMs: Long,
        val devicesSeen: Int,
        val namedSeen: Int,
        val nearMisses: Int,
        val targetBonded: Boolean,
        val scanFailedCode: Int? = null,
    )

    /**
     * The one reason a NOT_FOUND notification should name. Several can be true
     * at once, so the order below is the whole content of this type.
     */
    enum class NotFoundReason {
        /** `onScanFailed` fired. The code says which kind of failure. */
        SCAN_FAILED,

        /**
         * Something band-shaped was seen but did not match. Most actionable
         * non-failure signal: the band is advertising, this build just does not
         * recognise the name or address it is using.
         */
        NEAR_MISS,

        /**
         * The scan did not run its full window, so the process was killed or
         * the radio went away underneath it. Reported ahead of the device
         * counts because those counts are meaningless for a truncated window.
         */
        WINDOW_CUT_SHORT,

        /**
         * A full window and not one single device. Almost always Android's
         * scan throttle (5 starts per 30s, silently starved after that) or a
         * MIUI block on background scanning. Repeated icon taps while testing
         * cause exactly this.
         */
        NOTHING_SEEN,

        /**
         * The scan worked, other devices were seen, the band was not among
         * them. The expected case when the band is connected: a connected BLE
         * peripheral stops advertising.
         */
        SEEN_BUT_NO_BAND,
    }

    /**
     * A window is "cut short" when it ran for strictly less than this fraction
     * of what was requested. Expressed as a numerator/denominator pair so the
     * comparison stays in integer arithmetic - no rounding, and no false
     * positive at the boundary.
     */
    private const val CUT_SHORT_NUMERATOR = 6L
    private const val CUT_SHORT_DENOMINATOR = 10L

    fun whyNotFound(facts: ScanFacts): NotFoundReason = when {
        facts.scanFailedCode != null -> NotFoundReason.SCAN_FAILED

        // Ahead of the window check: seeing anything band-shaped proves the
        // scan was delivering results, which makes a short window a footnote
        // rather than the explanation.
        facts.nearMisses > 0 -> NotFoundReason.NEAR_MISS

        // elapsed < window * 6/10, computed without floating point. A zero
        // window can never satisfy this, so a degenerate caller is safe.
        facts.elapsedMs * CUT_SHORT_DENOMINATOR <
            facts.windowMs * CUT_SHORT_NUMERATOR -> NotFoundReason.WINDOW_CUT_SHORT

        facts.devicesSeen == 0 -> NotFoundReason.NOTHING_SEEN

        else -> NotFoundReason.SEEN_BUT_NO_BAND
    }
}
