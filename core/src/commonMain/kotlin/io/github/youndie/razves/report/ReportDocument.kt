package io.github.youndie.razves.report

import io.github.youndie.razves.read.SectionKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A report as data: the rows, without the symbols behind them.
 *
 * One format, two uses. This is what `--format json` prints and what the Gradle plugin commits as a
 * baseline for the diff to compare against, and it is deliberately the same type — a separate
 * "export" shape would drift from the model within a release, and the drift would show up as a diff
 * that quietly compares different things.
 *
 * **No derived numbers are stored.** Coverage is `attributed / size` and percentages are shares of a
 * total; storing them would put a rounded float in a file that has to round-trip byte for byte, and
 * would let a stored value and a computed one disagree. The renderer computes them.
 *
 * [formatVersion] exists because a baseline written by one release is read by the next.
 */
@Serializable
public data class ReportDocument(
    val formatVersion: Int = FORMAT_VERSION,
    val binary: String,
    val format: String,
    val sizeAlgorithm: String,
    val targets: List<String>,
    val hasSymbolTable: Boolean,
    val moduleAttribution: Boolean,
    /** Null when package names were not truncated. */
    val packageDepth: Int? = null,
    val fileSize: Long,
    val headerBytes: Long,
    val allocatedBytes: Long,
    val nobitsBytes: Long,
    val metadataBytes: Long,
    val paddingBytes: Long,
    val unparsedBytes: Long,
    val attributedBytes: Long,
    val unattributedBytes: Long,
    val sections: List<SectionEntry>,
    val origins: List<Row>,
    val packages: List<Row>,
    val modules: List<ModuleEntry>,
) {
    public companion object {
        public const val FORMAT_VERSION: Int = 1

        /** Stable on purpose: a baseline file that reformats itself produces a diff with no change in it. */
        public val JSON: Json =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
                encodeDefaults = true
            }

        public fun of(report: SizeReport): ReportDocument {
            val r = report.reconciliation
            return ReportDocument(
                binary = report.image.name,
                format = report.image.format.name,
                sizeAlgorithm = report.image.sizeAlgorithm.name,
                targets = report.image.targets.sorted(),
                hasSymbolTable = report.image.hasSymbolTable,
                moduleAttribution = report.hasModuleAttribution,
                packageDepth = report.packageDepth.takeIf { it != Int.MAX_VALUE },
                fileSize = r.fileSize,
                headerBytes = r.headerBytes,
                allocatedBytes = r.allocatedBytes,
                nobitsBytes = r.nobitsBytes,
                metadataBytes = r.metadataBytes,
                paddingBytes = r.interRegionPadding,
                unparsedBytes = r.unparsedBytes,
                attributedBytes = r.attributedBytes,
                unattributedBytes = r.unattributedBytes,
                sections =
                    r.sections
                        .filter { it.section.size > 0 }
                        .map {
                            SectionEntry(
                                name = it.section.qualifiedName,
                                kind = it.section.kind.name,
                                size = it.section.size,
                                attributed = it.attributed,
                            )
                        }.sortedWith(compareByDescending<SectionEntry> { it.size }.thenBy { it.name }),
                origins = report.origins.map { Row(it.origin.name, it.bytes, it.symbols) },
                packages = report.packages.map { Row(it.name, it.bytes, it.symbols) },
                modules = report.modules.map { ModuleEntry(it.name, it.bytes, it.symbols, it.kind.name) },
            )
        }

        public fun parse(text: String): ReportDocument = JSON.decodeFromString(serializer(), text)
    }

    public fun toJson(): String = JSON.encodeToString(serializer(), this)

    /** The sections a symbol claims nothing of, largest first. Never summed into one remainder. */
    public val unownedSections: List<SectionEntry>
        get() =
            sections
                .filter { it.kind == SectionKind.ALLOCATED.name && it.attributed == 0L }
                .sortedByDescending { it.size }

    @Serializable
    public data class SectionEntry(
        val name: String,
        val kind: String,
        val size: Long,
        val attributed: Long,
    ) {
        val unattributed: Long get() = size - attributed

        /** Printed beside every section conclusion: 98% and 41% must not look alike. */
        val coverage: Double get() = if (size == 0L) 1.0 else attributed.toDouble() / size
    }

    @Serializable
    public data class Row(
        val name: String,
        val bytes: Long,
        val symbols: Int,
    )

    @Serializable
    public data class ModuleEntry(
        val name: String,
        val bytes: Long,
        val symbols: Int,
        val kind: String,
    )
}
