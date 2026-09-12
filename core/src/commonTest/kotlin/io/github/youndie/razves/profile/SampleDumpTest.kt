package io.github.youndie.razves.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SampleDumpTest {
    private val header =
        """
        razves-samples 1
        binary /opt/app/app.kexe
        taken 2298
        dropped 17
        hz 1000
        clock wall
        """.trimIndent()

    @Test
    fun aDumpIsItsHeaderAndItsStacks() {
        val dump = SampleDump.parse("$header\n0x401234 0x401100\n0x4013f0\n")

        assertEquals("/opt/app/app.kexe", dump.binary)
        assertEquals(2298, dump.taken)
        assertEquals(17, dump.dropped)
        assertEquals(1000, dump.hz)
        assertEquals("wall", dump.clock)
        assertEquals(2, dump.stacks.size)
        assertEquals(listOf(0x401234L, 0x401100L), dump.stacks[0].toList())
    }

    @Test
    fun whatTheCollectorDidComesThroughWithWhatWasMissed() {
        // The missed count is the point. There is no listener in the runtime to subscribe to, only a
        // last-collection to ask about, so a program polling slower than it collects loses some - and
        // a reader dividing a pause total by a short count divides by the wrong number.
        val dump =
            SampleDump.parse(
                "$header\ngc 7 100 250 40 1867 11010048 6946816\ngc 9 300 460 55 1515 9000000 7000000\n" +
                    "gc-missed 343\n0x401234\n",
            )

        assertEquals(2, dump.collections.size)
        assertEquals(343, dump.missedCollections)
        val first = dump.collections.first()
        assertEquals(7, first.epoch)
        assertEquals(150, first.durationNs)
        assertEquals(11010048 - 6946816L, first.freedBytes)
    }

    @Test
    fun aFileThatIsNotADumpIsRefusedByName() {
        // A dump razves misreads as zero samples looks exactly like a program that was idle.
        val failure = assertFailsWith<IllegalArgumentException> { SampleDump.parse("hello\n", "/tmp/x") }
        assertTrue("/tmp/x" in failure.message.orEmpty())
        assertTrue("razves-samples" in failure.message.orEmpty())
    }

    @Test
    fun aDumpFromALaterFormatIsRefusedByVersion() {
        val failure =
            assertFailsWith<IllegalArgumentException> { SampleDump.parse("razves-samples 2\n", "/tmp/x") }
        assertTrue("version 2" in failure.message.orEmpty())
    }

    @Test
    fun aLineRazvesDoesNotUnderstandStopsItRatherThanBeingSkipped() {
        // Skipping the unknown is how a format grows a silent dialect: the writer emits something the
        // reader drops, and the numbers are quietly of less than the run.
        val failure =
            assertFailsWith<IllegalStateException> { SampleDump.parse("$header\nwat 3\n", "/tmp/x") }
        assertTrue("does not understand" in failure.message.orEmpty())
    }

    @Test
    fun aGcLineWithTheWrongNumberOfFieldsIsRefused() {
        val failure =
            assertFailsWith<IllegalArgumentException> { SampleDump.parse("$header\ngc 7 100 250\n", "/tmp/x") }
        assertTrue("fields" in failure.message.orEmpty())
    }
}
