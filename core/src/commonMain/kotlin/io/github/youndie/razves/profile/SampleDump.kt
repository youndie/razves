package io.github.youndie.razves.profile

/**
 * What a sampled process leaves behind, and what razves picks up.
 *
 * **Text, and deliberately.** A binary format would be smaller and would need a reader before anybody
 * could see whether the sampler worked at all; this one is greppable, diffable, and readable by a
 * person who suspects the addresses are wrong. The profiles it produces are a few megabytes at
 * thirty thousand samples, which is a cost worth paying for being able to look.
 *
 * ```
 * razves-samples 1
 * binary /opt/app/app.kexe
 * taken 21340
 * dropped 17245
 * hz 1000
 * clock wall
 * 0x401234 0x401100 0x4010c0
 * 0x4013f0 0x401100 0x4010c0
 * ```
 *
 * **The header carries what only the sampled process knows** and razves cannot recover afterwards:
 * how many signals arrived, how many the ring could not hold, at what rate, on which clock. A profile
 * that lost two thirds of its samples has to say so, and by the time the file reaches razves the
 * sampler is gone.
 *
 * Leaf frame first in every line, which is the order `backtrace()` returns.
 */
public data class SampleDump(
    val binary: String?,
    val taken: Long,
    val dropped: Long,
    val hz: Int?,
    val clock: String?,
    val stacks: List<LongArray>,
    /**
     * How far the image was loaded from where it was linked, which is zero everywhere except Apple.
     *
     * A Kotlin/Native Linux executable is `ET_EXEC` and does not move; a macOS one is `MH_PIE` and the
     * loader picks a slide. razves reads link-time addresses out of the binary and cannot know the
     * slide; the process knew it and is gone by the time the profile is read - so it travels here,
     * and [stacks] are already relative to the binary.
     */
    val slide: Long = 0,
    /** What the collector did, if the process was watching it. */
    val collections: List<GcCollection> = emptyList(),
    /**
     * Collections the runtime performed that the process never saw.
     *
     * There is no listener in the runtime to subscribe to - only `GC.lastGCInfo` to ask - so a poll
     * slower than the collection rate loses some, and a reader who divides by the count without this
     * number divides by the wrong one.
     */
    val missedCollections: Long = 0,
) {
    /**
     * The rate the sampler actually achieved, if the file says enough to compute one.
     *
     * Not the rate that was asked for: a CPU-time clock saturates at about 200 Hz whatever it is set
     * to ([research §1.3](../../../../../../../docs/research/research-profiler.md)), and a profile
     * quoting the request rather than the delivery has percentages that mean nothing.
     */
    public val stacksKept: Int get() = stacks.size

    public companion object {
        public const val MAGIC: String = "razves-samples"
        public const val VERSION: Int = 1

        /**
         * Parses a dump, refusing anything it cannot read by name rather than by returning an empty
         * profile - a file razves misreads as zero samples looks exactly like a program that was idle.
         */
        public fun parse(
            text: String,
            path: String = "the dump",
        ): SampleDump {
            val lines = text.lineSequence().iterator()
            require(lines.hasNext()) { "$path is empty; a dump has at least a header line" }
            val header = lines.next().trim().split(' ')
            require(header.size == 2 && header[0] == MAGIC) {
                "$path does not start with \"$MAGIC <version>\", so it is not a razves sample dump"
            }
            val version = header[1].toIntOrNull()
            require(version == VERSION) {
                "$path is dump format version ${header[1]} and this razves reads $VERSION"
            }

            var binary: String? = null
            var taken = 0L
            var dropped = 0L
            var hz: Int? = null
            var clock: String? = null
            val stacks = ArrayList<LongArray>()
            val collections = ArrayList<GcCollection>()
            var missed = 0L
            var slide = 0L

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("binary ") -> binary = line.removePrefix("binary ").trim()
                    line.startsWith("taken ") -> taken = number(line, "taken", path)
                    line.startsWith("dropped ") -> dropped = number(line, "dropped", path)
                    line.startsWith("hz ") -> hz = number(line, "hz", path).toInt()
                    line.startsWith("clock ") -> clock = line.removePrefix("clock ").trim()
                    line.startsWith("slide ") -> slide = number(line, "slide", path)
                    line.startsWith("gc-missed ") -> missed = number(line, "gc-missed", path)
                    line.startsWith("gc ") -> collections += collection(line, path)
                    line.startsWith("0x") -> stacks += frames(line, path)
                    else -> error("$path has a line razves does not understand: \"$line\"")
                }
            }
            // Subtracted once, here, rather than by every reader: a stack that reached razves in
            // runtime addresses and a stack that reached it in link-time ones look identical, and
            // the one that was not corrected resolves to nothing at all.
            val corrected =
                if (slide == 0L) stacks else stacks.map { frames -> LongArray(frames.size) { frames[it] - slide } }
            return SampleDump(
                binary = binary,
                taken = taken,
                dropped = dropped,
                hz = hz,
                clock = clock,
                stacks = corrected,
                slide = slide,
                collections = collections,
                missedCollections = missed,
            )
        }

        private fun number(
            line: String,
            key: String,
            path: String,
        ): Long =
            line.removePrefix("$key ").trim().toLongOrNull()
                ?: error("$path has a $key that is not a number: \"$line\"")

        /** `gc <epoch> <start> <end> <pause> <marked> <heapBefore> <heapAfter>`, all in nanoseconds and bytes. */
        private fun collection(
            line: String,
            path: String,
        ): GcCollection {
            val parts = line.split(' ').filter { it.isNotBlank() }
            require(parts.size == 8) {
                "$path has a gc line with ${parts.size - 1} fields rather than 7: \"$line\""
            }
            val numbers =
                LongArray(7) { i ->
                    parts[i + 1].toLongOrNull()
                        ?: error("$path has a gc line with a field that is not a number: \"$line\"")
                }
            return GcCollection(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5], numbers[6])
        }

        private fun frames(
            line: String,
            path: String,
        ): LongArray {
            val parts = line.split(' ').filter { it.isNotBlank() }
            return LongArray(parts.size) { i ->
                val text = parts[i].removePrefix("0x")
                // Addresses above 0x7fff_ffff_ffff_ffff do not occur on any target razves reads, and a
                // parse that silently wrapped one would produce a frame in a place no binary is.
                text.toULongOrNull(16)?.toLong()
                    ?: error("$path has a frame that is not a hexadecimal address: \"${parts[i]}\"")
            }
        }
    }
}

/**
 * One collection, as `kotlin.native.runtime.GCInfo` described it to the process that was watching.
 *
 * The pause is both stop-the-world windows added together - a cycle has one or two, and the second is
 * absent often enough that reporting only the first understates what the program felt.
 */
public data class GcCollection(
    val epoch: Long,
    val startTimeNs: Long,
    val endTimeNs: Long,
    val pauseNs: Long,
    val markedCount: Long,
    val heapBeforeBytes: Long,
    val heapAfterBytes: Long,
) {
    val durationNs: Long get() = endTimeNs - startTimeNs

    val freedBytes: Long get() = heapBeforeBytes - heapAfterBytes
}
