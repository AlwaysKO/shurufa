package com.yuyan.inputmethod

import com.yuyan.imemodule.data.completion.OfflineAssociationQuery

internal data class MergedAssociationCandidate(
    val text: String,
    val source: AssociationCandidateSource,
    val rimeIndex: Int? = null,
)

internal enum class AssociationCandidateSource { OFFLINE, RIME, CUSTOM, REMOTE }

internal object AssociationCandidateMerger {
    fun merge(
        offline: OfflineAssociationQuery,
        rime: List<String>,
        custom: List<String>,
        remote: List<String>,
    ): List<MergedAssociationCandidate> = buildList {
        val seen = HashSet<String>()
        fun addPlain(candidates: Iterable<String>, source: AssociationCandidateSource) {
            candidates.filter { it.isNotBlank() && seen.add(it) }
                .forEach { add(MergedAssociationCandidate(it, source)) }
        }
        fun addRime(range: IntRange) {
            range.filter { it in rime.indices }
                .filter { seen.add(rime[it]) }
                .forEach { add(MergedAssociationCandidate(rime[it], AssociationCandidateSource.RIME, it)) }
        }

        val partial = offline.partial.filterNot { it in offline.lowPriority }
        val next = offline.next.filterNot { it in offline.lowPriority }
        when {
            partial.isNotEmpty() -> {
                addPlain(partial, AssociationCandidateSource.OFFLINE)
                addRime(0..4)
                addPlain(custom, AssociationCandidateSource.CUSTOM)
                addPlain(next, AssociationCandidateSource.OFFLINE)
                addRime(5 until rime.size)
            }
            next.isNotEmpty() && offline.trailingPunctuation -> {
                addPlain(next, AssociationCandidateSource.OFFLINE)
                addRime(0..4)
                addPlain(custom, AssociationCandidateSource.CUSTOM)
                addRime(5 until rime.size)
            }
            next.isNotEmpty() -> {
                addPlain(custom, AssociationCandidateSource.CUSTOM)
                addPlain(next, AssociationCandidateSource.OFFLINE)
                addRime(rime.indices)
            }
            else -> {
                addRime(0..4)
                addPlain(custom, AssociationCandidateSource.CUSTOM)
                addRime(5 until rime.size)
            }
        }
        addPlain(offline.lowPriority, AssociationCandidateSource.OFFLINE)
        addPlain(remote, AssociationCandidateSource.REMOTE)
    }
}
