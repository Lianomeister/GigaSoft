package com.clockwork.runtime

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        return compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
    }

    companion object {
        fun parse(raw: String): SemVer? {
            val base = raw.trim().substringBefore('-').substringBefore('+')
            val pieces = base.split('.')
            if (pieces.isEmpty() || pieces.size > 3) return null
            val nums = pieces.map { it.toIntOrNull() ?: return null }
            val major = nums.getOrElse(0) { 0 }
            val minor = nums.getOrElse(1) { 0 }
            val patch = nums.getOrElse(2) { 0 }
            return SemVer(major, minor, patch)
        }
    }
}

object VersionRange {
    fun matches(version: String, expression: String): Boolean {
        val parsedVersion = SemVer.parse(version) ?: return false
        val clauses = expression.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (clauses.isEmpty()) return true
        return clauses.all { clause -> evaluateClause(parsedVersion, clause) }
    }

    private fun evaluateClause(version: SemVer, clause: String): Boolean {
        val matcher = Regex("^(>=|<=|>|<|==|=)(.+)$").matchEntire(clause.trim()) ?: return false
        val operator = matcher.groupValues[1]
        val target = SemVer.parse(matcher.groupValues[2].trim()) ?: return false
        return when (operator) {
            ">" -> version > target
            ">=" -> version >= target
            "<" -> version < target
            "<=" -> version <= target
            "=", "==" -> version == target
            else -> false
        }
    }
}

