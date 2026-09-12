package io.github.youndie.razves.sampler

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import razves.sampler.native.razves_depth_at
import razves.sampler.native.razves_dropped_count
import razves.sampler.native.razves_frame_at
import razves.sampler.native.razves_image_slide
import razves.sampler.native.razves_is_armed
import razves.sampler.native.razves_mark_read
import razves.sampler.native.razves_slots
import razves.sampler.native.razves_start
import razves.sampler.native.razves_stop
import razves.sampler.native.razves_taken_count

/**
 * Which clock the sampler fires on, and the two questions are not the same question.
 *
 * [WALL] fires whether the thread is running or blocked, and is the only one that goes faster than
 * the scheduler tick: 8,204 Hz delivered for 10,000 requested. [CPU] fires only while this thread
 * runs, which is what a CPU profile means, and saturates at about 200 Hz however high you set it -
 * a CPU clock is advanced on the tick. Both measured, research 1.3.
 */
public enum class SamplingClock { WALL, CPU }

/**
 * The in-process half: a timer, a signal handler and a fixed ring of stacks.
 *
 * **Everything here is a call into C** ([the def file](../../../../../../nativeInterop/cinterop/sampler.def)),
 * because a Kotlin signal handler deadlocks against the allocator it interrupts - measured at 3
 * hung runs in 10 at 100 Hz. This class is the outside of that wall: it arms, it disarms, and it
 * reads what the handler wrote, all from ordinary Kotlin on an ordinary thread.
 *
 * **No symbol is resolved here and no allocation happens on the sampled path.** Raw addresses leave
 * the process; razves turns them into packages afterwards, out of the same symbol table it reads to
 * size a binary.
 */
@OptIn(ExperimentalForeignApi::class)
public object Sampler {
    /** How many samples the ring holds before the handler starts dropping them. */
    public val capacity: Long get() = razves_slots().toLong()

    public val armed: Boolean get() = razves_is_armed() != 0

    /**
     * How far this image was loaded from where it was linked.
     *
     * Zero on Linux, where a Kotlin/Native executable is `ET_EXEC` and nothing moves. On Apple targets
     * the binary is `MH_PIE` and the loader picks a slide: razves knows the link-time addresses and
     * cannot know the slide, this process knows the slide and will not be there when the profile is
     * read - so it goes in the dump and razves subtracts it.
     */
    public val imageSlide: Long get() = razves_image_slide().toLong()

    /** Signals delivered since the process started, dropped ones included. */
    public val taken: Long get() = razves_taken_count().toLong()

    /**
     * Samples the handler had nowhere to put.
     *
     * Part of the profile rather than a log line: a profile that lost a third of its samples and
     * does not say so is a profile whose percentages are wrong in a way nobody can see.
     */
    public val dropped: Long get() = razves_dropped_count().toLong()

    /**
     * Arms the timer at [hz] on [clock].
     *
     * The rate is a request. What arrives is counted, and [taken] over the elapsed time is the rate
     * a profile should quote - asking for 1000 Hz on a CPU clock gets about 219.
     */
    public fun start(
        hz: Int,
        clock: SamplingClock = SamplingClock.WALL,
    ) {
        val code = razves_start(hz, if (clock == SamplingClock.CPU) 1 else 0)
        check(code == 0) { "the sampler did not start: $code (see razves_start in sampler.def)" }
    }

    /** Idempotent, because a sampler left armed in somebody else process is the failure that matters. */
    public fun stop(): Unit = razves_stop()

    /**
     * The samples as the text dump razves reads, header and all.
     *
     * **Built here rather than by `core`, because `core` is not welcome in somebody else process.**
     * The format is defined by `SampleDump` on the reading side; this is four lines of string
     * building, which is a cheaper price than putting a serialisation library in the address space of
     * a program that only wanted to be measured.
     *
     * The header carries what only this process knows and razves cannot recover afterwards: signals
     * delivered, samples the ring could not hold, the rate asked for and the clock it was asked on.
     */
    public fun dump(
        binaryPath: String? = null,
        hz: Int? = null,
        clock: SamplingClock? = null,
        extra: List<String> = emptyList(),
    ): String =
        buildString {
            appendLine("razves-samples 1")
            binaryPath?.let { appendLine("binary $it") }
            appendLine("slide $imageSlide")
            appendLine("taken $taken")
            appendLine("dropped $dropped")
            hz?.let { appendLine("hz $it") }
            clock?.let { appendLine("clock ${it.name.lowercase()}") }
            // Whatever else the process knows and razves cannot recover - today the collector
            // ([GcWatch]), tomorrow whatever the next thing turns out to be.
            extra.forEach { appendLine(it) }
            for (stack in drain()) {
                appendLine(stack.joinToString(" ") { "0x" + it.toULong().toString(16) })
            }
        }

    /**
     * Writes [dump] to a file, through libc rather than through a file-system library.
     *
     * One more thing this module does not depend on. A profiled program has its own opinions about
     * what it links, and the sampler earns its place by asking for nothing.
     */
    public fun writeDump(
        path: String,
        binaryPath: String? = null,
        hz: Int? = null,
        clock: SamplingClock? = null,
    ): Unit = writeText(path, dump(binaryPath, hz, clock))

    /** The same write, for a caller that already has the text and wants to count what is in it. */
    public fun writeText(
        path: String,
        text: String,
    ) {
        val file = fopen(path, "w") ?: error("razves could not open $path for writing")
        try {
            fputs(text, file)
        } finally {
            fclose(file)
        }
    }

    /**
     * Every sample still in the ring, leaf frame first, and marks them read.
     *
     * Returns stacks rather than a flat list: a profile needs the path to the leaf, and a sampler
     * that threw the path away would leave the aggregation with nothing to group by.
     */
    public fun drain(): List<LongArray> {
        val end = razves_taken_count()
        val slots = razves_slots()
        val first = if (end > slots) end - slots else 0uL
        val out = ArrayList<LongArray>((end - first).toInt())
        var i = first
        while (i < end) {
            val depth = razves_depth_at(i).toInt()
            if (depth > 0) {
                out += LongArray(depth) { frame -> razves_frame_at(i, frame.toUInt()).toLong() }
            }
            i++
        }
        razves_mark_read(end)
        return out
    }
}
