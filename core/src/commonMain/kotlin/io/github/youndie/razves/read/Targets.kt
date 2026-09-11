package io.github.youndie.razves.read

/**
 * Which Kotlin/Native targets a binary could have been built for, in the names a klib manifest uses.
 *
 * Usually one, sometimes two, occasionally none. The point is not to label the binary but to answer
 * one question: **could these klibs have produced it?** A `linuxX64` binary reported against
 * `macosArm64` klibs produces module rows that are plausible and wrong, and there is nothing in the
 * output to suggest it.
 *
 * The mapping is deliberately conservative. A container plus a CPU is not always enough to name one
 * target — an ELF x86-64 file is `linux_x64` or `android_x64` and razves cannot tell which, so it
 * says both — and when it is not enough for even that, the answer is the empty set and no refusal
 * follows. A check that cannot identify the subject must not veto it.
 */
internal object Targets {
    private const val EM_X86_64 = 0x3E
    private const val EM_AARCH64 = 0xB7
    private const val EM_ARM = 0x28

    private const val CPU_TYPE_X86_64 = 0x0100_0007
    private const val CPU_TYPE_ARM64 = 0x0100_000C
    private const val CPU_TYPE_ARM = 0x0000_000C

    // Mach-O platform identifiers, as LC_BUILD_VERSION spells them.
    private const val PLATFORM_MACOS = 1
    private const val PLATFORM_IOS = 2
    private const val PLATFORM_TVOS = 3
    private const val PLATFORM_WATCHOS = 4
    private const val PLATFORM_IOS_SIMULATOR = 7
    private const val PLATFORM_TVOS_SIMULATOR = 8
    private const val PLATFORM_WATCHOS_SIMULATOR = 9

    /**
     * ELF means Linux or Android to Kotlin/Native, and the container says nothing about which. Both
     * are named rather than one guessed at, so a mismatch is only reported when neither can be true.
     */
    fun ofElf(machine: Int): Set<String> =
        when (machine) {
            EM_X86_64 -> setOf("linux_x64", "android_x64")
            EM_AARCH64 -> setOf("linux_arm64", "android_arm64")
            EM_ARM -> setOf("linux_arm32_hfp", "android_arm32")
            else -> emptySet()
        }

    /**
     * Mach-O needs both halves: the CPU from the header, the operating system from
     * `LC_BUILD_VERSION`. Without the load command — an older binary, or one built by something that
     * does not emit it — the platform is unknown and so is the target.
     */
    fun ofMachO(
        cpuType: Int,
        platform: Int?,
    ): Set<String> {
        val cpu =
            when (cpuType) {
                CPU_TYPE_X86_64 -> "x64"
                CPU_TYPE_ARM64 -> "arm64"
                CPU_TYPE_ARM -> "arm32"
                else -> return emptySet()
            }
        return when (platform) {
            PLATFORM_MACOS -> setOf("macos_$cpu")

            PLATFORM_IOS -> setOf("ios_$cpu")

            PLATFORM_TVOS -> setOf("tvos_$cpu")

            PLATFORM_WATCHOS -> setOf(if (cpu == "arm32") "watchos_arm32" else "watchos_$cpu")

            // A simulator on Apple silicon is its own Kotlin target; on Intel it shares the name of
            // the device target, which is why these two are not one line.
            PLATFORM_IOS_SIMULATOR -> setOf(if (cpu == "arm64") "ios_simulator_arm64" else "ios_$cpu")

            PLATFORM_TVOS_SIMULATOR -> setOf(if (cpu == "arm64") "tvos_simulator_arm64" else "tvos_$cpu")

            PLATFORM_WATCHOS_SIMULATOR -> setOf(if (cpu == "arm64") "watchos_simulator_arm64" else "watchos_$cpu")

            else -> emptySet()
        }
    }
}
