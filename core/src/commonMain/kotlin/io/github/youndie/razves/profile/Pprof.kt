package io.github.youndie.razves.profile

import io.github.youndie.razves.attribute.Mangling
import io.github.youndie.razves.klib.Klib
import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.report.SymbolIndex

/**
 * The profile as pprof, so that the viewers people already run can open it.
 *
 * A profiler nobody can look at is a file nobody keeps. This writes what `go tool pprof`, Speedscope
 * and the browser profilers read, out of the same stacks [Profiling] aggregates - the razves rows and
 * this file are two renderings of one measurement, not two measurements.
 *
 * **Names are the Kotlin ones.** `Function.name` is the symbol with its `kfun:` prefix removed, and
 * `Function.system_name` is the mangled symbol exactly as the binary carries it - which is what that
 * field is for, and what lets a reader check a name rather than trust it.
 *
 * **A frame razves cannot name still gets a function**, called what it is: one of the two rows the
 * aggregation uses. A viewer with a gap where the loader should be is a viewer whose percentages do
 * not add up, and the reader has no way to tell which frames were dropped.
 */
public object Pprof {
    /**
     * @param stacks one array per sample, leaf frame first.
     * @param period the sampling interval in nanoseconds, or null when it is not known. Written as
     *   pprof's `period`, which is what a viewer multiplies by to turn counts into time - so it is
     *   left out rather than guessed at.
     * @param dropped what the sampler could not keep. pprof has no field for it and a profile that
     *   silently lost a third of its samples is a profile whose percentages are wrong, so it goes in
     *   the `comment` field - which every viewer shows and none interprets.
     */
    public fun of(
        image: BinaryImage,
        stacks: List<LongArray>,
        klibs: Collection<Klib>? = null,
        period: Long? = null,
        dropped: Long = 0,
    ): ByteArray {
        val index = SymbolIndex.of(image)
        val loaded = Loaded(image)

        val strings = StringTable()
        strings.of("")
        val samplesType = strings.of("samples")
        val countUnit = strings.of("count")
        val cpuType = strings.of("cpu")
        val nanosecondsUnit = strings.of("nanoseconds")

        // One Function and one Location per distinct name, so that a viewer folds recursion and
        // repeated frames the way it folds everything else.
        val functionIds = LinkedHashMap<String, Long>()
        val functionNames = ArrayList<Pair<Int, Int>>()
        val locationIds = LinkedHashMap<String, Long>()

        fun locationOf(address: Long): Long {
            val symbol = index.at(address)
            val display: String
            val system: String
            if (symbol != null) {
                display = Mangling.kotlinBodyOf(symbol.name) ?: symbol.name
                system = symbol.name
            } else {
                display = if (loaded.contains(address)) Profile.NO_SYMBOL else Profile.OUTSIDE
                system = display
            }
            return locationIds.getOrPut(display) {
                val functionId =
                    functionIds.getOrPut(display) {
                        val id = (functionIds.size + 1).toLong()
                        functionNames += strings.of(display) to strings.of(system)
                        id
                    }
                functionId
            }
        }

        // Identical stacks become one sample with a count, which is what a profile of a long run is
        // mostly made of - and what keeps the file from being one entry per signal delivered.
        val counted = LinkedHashMap<List<Long>, Long>()
        for (stack in stacks) {
            if (stack.isEmpty()) continue
            val locations = stack.map { locationOf(it) }
            counted[locations] = (counted[locations] ?: 0) + 1
        }

        val profile = Protobuf()
        profile.lengthDelimited(1, Protobuf().varint(1, samplesType.toLong()).varint(2, countUnit.toLong()).bytes())
        counted.forEach { (locations, count) ->
            val sample =
                Protobuf()
                    .packed(1, locations)
                    .packed(2, listOf(count))
            profile.lengthDelimited(2, sample.bytes())
        }
        locationIds.values.forEach { id ->
            val line = Protobuf().varint(1, id).bytes()
            profile.lengthDelimited(4, Protobuf().varint(1, id).lengthDelimited(4, line).bytes())
        }
        functionNames.forEachIndexed { i, (name, system) ->
            val function =
                Protobuf()
                    .varint(1, (i + 1).toLong())
                    .varint(2, name.toLong())
                    .varint(3, system.toLong())
            profile.lengthDelimited(5, function.bytes())
        }
        val comment =
            if (dropped > 0) {
                strings.of("razves: $dropped samples were dropped by the ring buffer and are not in this file")
            } else {
                null
            }
        strings.all().forEach { profile.lengthDelimited(6, it.encodeToByteArray()) }
        comment?.let { profile.varint(13, it.toLong()) }
        if (period != null) {
            profile.lengthDelimited(
                11,
                Protobuf().varint(1, cpuType.toLong()).varint(2, nanosecondsUnit.toLong()).bytes(),
            )
            profile.varint(12, period)
        }

        return GzipStored.wrap(profile.bytes())
    }

    /** pprof addresses every string by index, and index 0 is the empty string. */
    private class StringTable {
        private val indices = LinkedHashMap<String, Int>()

        fun of(value: String): Int = indices.getOrPut(value) { indices.size }

        fun all(): List<String> = indices.keys.toList()
    }
}
