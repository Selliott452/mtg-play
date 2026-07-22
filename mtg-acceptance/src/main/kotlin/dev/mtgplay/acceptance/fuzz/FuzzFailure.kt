package dev.mtgplay.acceptance.fuzz

import java.nio.file.Path

/**
 * The loud abort a fuzz corpus run raises when a seed fails (deliverable 3 of P3.3): an invariant
 * violation, an enumeration-completeness [ProbeFailure], or any other engine throwable.
 *
 * By the time this is thrown the failure has already been persisted to [reproPath] as a
 * self-contained [FuzzRepro]. The message carries both that path *and* an inline summary of the
 * repro, so a CI log shows the essentials — corpus, seed, failure detail, fingerprint, decision
 * count — without opening the file, while the file holds the full replay record. The original
 * throwable is preserved as the [cause].
 *
 * @property corpusName the corpus the failing seed belonged to.
 * @property seed the seed that failed (ADR-006 — the entry point to reproducing it).
 * @property reproPath the persisted repro file (deliverable 3).
 */
class FuzzFailure(
    val corpusName: String,
    val seed: Long,
    val reproPath: Path,
    inlineSummary: String,
    cause: Throwable,
) : AssertionError(
        buildString {
            append("fuzz corpus \"$corpusName\" failed on seed $seed\n")
            append(inlineSummary)
            append("\nfull repro written to: ").append(reproPath.toAbsolutePath())
        },
        cause,
    )
