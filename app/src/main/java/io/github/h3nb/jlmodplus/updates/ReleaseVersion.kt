package io.github.h3nb.jlmodplus.updates

/** SemVer 2.0 precedence. Build metadata never affects update eligibility. */
internal class ReleaseVersion private constructor(
    private val core: List<String>,
    private val pre: List<String>,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in core.indices) {
            val comparison = compareNumeric(core[i], other.core[i])
            if (comparison != 0) return comparison
        }
        if (pre.isEmpty() || other.pre.isEmpty()) {
            return when {
                pre.isEmpty() && other.pre.isNotEmpty() -> 1
                pre.isNotEmpty() && other.pre.isEmpty() -> -1
                else -> 0
            }
        }
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val numericA = a.all(Char::isDigit)
            val numericB = b.all(Char::isDigit)
            val comparison = when {
                numericA && numericB -> compareNumeric(a, b)
                numericA -> -1
                numericB -> 1
                else -> a.compareTo(b)
            }
            if (comparison != 0) return comparison
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        private val pattern = Regex(
            """(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?"""
        )

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val core = (1..3).map { match.groupValues[it] }
            val pre = match.groupValues[4].takeIf(String::isNotEmpty)
                ?.split('.') ?: emptyList()
            if (pre.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it[0] == '0') }) {
                return null
            }
            return ReleaseVersion(core, pre)
        }

        private fun compareNumeric(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}

internal enum class UpdateChannel { Stable, Preview }

internal data class UpdateCandidate(
    val versionName: String,
    val versionCode: Int,
    val releaseUrl: String,
    val downloadUrl: String? = null,
)

internal fun eligibleUpdate(
    installedName: String,
    installedCode: Int,
    candidate: UpdateCandidate,
): Boolean {
    val installed = ReleaseVersion.parse(installedName) ?: return false
    val remote = ReleaseVersion.parse(candidate.versionName) ?: return false
    return remote > installed && candidate.versionCode > installedCode
}
