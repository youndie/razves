package io.github.youndie.razves.read

/**
 * Which reader a file needs, decided by the file.
 *
 * **Never by an option and never by the host.** A caller who has to say what kind of binary they
 * handed razves can say it wrongly, and the file already knows: ELF starts with `7f 45 4c 46` and a
 * 64-bit Mach-O with its own magic. The same program built for two platforms is two formats, and a
 * test that hard-coded one of them is how this came to live here rather than inside the CLI.
 */
public object Binaries {
    public fun read(
        data: ByteArray,
        name: String,
    ): BinaryImage =
        when {
            ElfReader.matches(data) -> ElfReader.read(data, name)
            MachOReader.matches(data) -> MachOReader.read(data, name)
            else -> error("$name is neither an ELF nor a 64-bit Mach-O file; razves reads those two")
        }
}
