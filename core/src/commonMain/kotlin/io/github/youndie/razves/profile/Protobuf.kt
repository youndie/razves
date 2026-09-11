package io.github.youndie.razves.profile

/**
 * Just enough protobuf to write one, and no dependency to get it.
 *
 * pprof is a protobuf, and a library for it would be a dependency on the build classpath of every
 * consumer of `core` for the sake of three encodings: a varint, a length-delimited field, and a
 * packed list of varints. razves already reads protobuf-shaped klib metadata by hand
 * ([research §1.9](../../../../../../../docs/research/research-profiler.md)); this is the writing
 * direction of the same idea.
 */
internal class Protobuf {
    private val out = ArrayList<Byte>(1024)

    fun bytes(): ByteArray = out.toByteArray()

    fun varint(
        field: Int,
        value: Long,
    ): Protobuf =
        apply {
            if (value == 0L) return@apply // proto3 omits defaults, and pprof readers expect that
            tag(field, 0)
            writeVarint(value)
        }

    fun lengthDelimited(
        field: Int,
        payload: ByteArray,
    ): Protobuf =
        apply {
            tag(field, 2)
            writeVarint(payload.size.toLong())
            payload.forEach { out += it }
        }

    /** Repeated scalars in one length-delimited field, which is how pprof writes ids and values. */
    fun packed(
        field: Int,
        values: List<Long>,
    ): Protobuf =
        apply {
            if (values.isEmpty()) return@apply
            val inner = Protobuf()
            values.forEach { inner.writeVarint(it) }
            lengthDelimited(field, inner.bytes())
        }

    private fun tag(
        field: Int,
        wire: Int,
    ) = writeVarint(((field shl 3) or wire).toLong())

    private fun writeVarint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out += b.toByte()
                return
            }
            out += (b or 0x80).toByte()
        }
    }
}
