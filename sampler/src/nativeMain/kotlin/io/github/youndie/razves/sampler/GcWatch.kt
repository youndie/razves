package io.github.youndie.razves.sampler

import kotlin.native.runtime.GC
import kotlin.native.runtime.GCInfo

/**
 * What the collector did, by asking - because there is nothing to subscribe to.
 *
 * `kotlin.native.runtime.GC` offers `lastGCInfo` and nothing else: no listener, no callback, no event
 * stream, verified against 54,835 lines of the 2.4.10 standard library metadata
 * ([research §1.7](../../../../../../../docs/research/research-profiler.md)). So this is a poll, and
 * a poll has a defect built into it - two collections between two calls leave one invisible.
 *
 * **It says what it missed.** Epochs are consecutive integers, so the gap between the epoch this saw
 * last and the one it sees now is arithmetic rather than a guess. A profiler reporting four
 * collections where there were nine is worse than one reporting none, and the difference costs one
 * subtraction.
 *
 * **Ordinary Kotlin on an ordinary thread.** Unlike the sampler next door this touches no signal and
 * runs nothing in one: it is called by the program, between whatever it was doing anyway.
 */
@OptIn(ExperimentalStdlibApi::class, kotlin.native.runtime.NativeRuntimeApi::class)
public object GcWatch {
    private val seen = ArrayList<Collection>()
    private var lastEpoch: Long = -1
    private var firstEpoch: Long = -1
    private var missedCount: Long = 0

    /** One collection, as the runtime described it. */
    public data class Collection(
        val epoch: Long,
        val startTimeNs: Long,
        val endTimeNs: Long,
        /** Both stop-the-world windows added together; the second one is absent in some cycles. */
        val pauseNs: Long,
        val markedCount: Long,
        val heapBeforeBytes: Long,
        val heapAfterBytes: Long,
    ) {
        val durationNs: Long get() = endTimeNs - startTimeNs

        val freedBytes: Long get() = heapBeforeBytes - heapAfterBytes
    }

    /** Collections this watch actually saw, oldest first. */
    public val collections: List<Collection> get() = seen.toList()

    /**
     * Collections the runtime performed and this watch never saw, because they happened between two
     * polls. Part of every report that quotes a collection count.
     */
    public val missed: Long get() = missedCount

    /**
     * Reads the last collection, and returns it when it is one this watch has not seen.
     *
     * Cheap enough to call in a loop: on no collection since the last call it compares two longs and
     * returns null.
     */
    public fun poll(): Collection? {
        val info: GCInfo = GC.lastGCInfo ?: return null
        if (info.epoch == lastEpoch) return null
        if (lastEpoch >= 0) {
            // Epochs are consecutive, so anything beyond the next one happened unobserved.
            missedCount += info.epoch - lastEpoch - 1
        } else {
            firstEpoch = info.epoch
        }
        lastEpoch = info.epoch
        val collection =
            Collection(
                epoch = info.epoch,
                startTimeNs = info.startTimeNs,
                endTimeNs = info.endTimeNs,
                pauseNs =
                    (info.firstPauseEndTimeNs - info.firstPauseStartTimeNs) +
                        pauseOf(info.secondPauseStartTimeNs, info.secondPauseEndTimeNs),
                markedCount = info.markedCount,
                heapBeforeBytes = info.memoryUsageBefore.values.sumOf { it.totalObjectsSizeBytes },
                heapAfterBytes = info.memoryUsageAfter.values.sumOf { it.totalObjectsSizeBytes },
            )
        seen += collection
        return collection
    }

    private fun pauseOf(
        start: Long?,
        end: Long?,
    ): Long = if (start != null && end != null) end - start else 0

    /**
     * The collections as the lines razves reads out of a dump.
     *
     * The missed count is a line of its own rather than a comment: a reader who sees six collections
     * and does not know that four were missed will divide by six.
     */
    public fun lines(): List<String> =
        seen.map { c ->
            "gc ${c.epoch} ${c.startTimeNs} ${c.endTimeNs} ${c.pauseNs} ${c.markedCount} " +
                "${c.heapBeforeBytes} ${c.heapAfterBytes}"
        } + "gc-missed $missedCount"
}
