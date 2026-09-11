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

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("binary ") -> binary = line.removePrefix("binary ").trim()
                    line.startsWith("taken ") -> taken = number(line, "taken", path)
                    line.startsWith("dropped ") -> dropped = number(line, "dropped", path)
                    line.startsWith("hz ") -> hz = number(line, "hz", path).toInt()
                    line.startsWith("clock ") -> clock = line.removePrefix("clock ").trim()
                    line.startsWith("0x") -> stacks += frames(line, path)
                    else -> error("$path has a line razves does not understand: \"$line\"")
                }
            }
            return SampleDump(binary, taken, dropped, hz, clock, stacks)
        }

        private fun number(
            line: String,
            key: String,
            path: String,
        ): Long =
            line.removePrefix("$key ").trim().toLongOrNull()
                ?: error("$path has a $key that is not a number: \"$line\"")

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
