package com.tibatech.gptmax.core.agent

/**
 * Phase 5 (external-review roadmap M5 "independent review pass with explicit
 * uncertainty"): turns the reviewer's free text into a decision.
 *
 * Phase 1-4 runs logged the review and then emitted `Completed` regardless
 * of what the reviewer said — an agent could report success on a change the
 * reviewer had just rejected. Classification closes that gap:
 *
 * - "VERDICT: APPROVED" / "VERDICT: CHANGES_NEEDED" (the protocol the phase-5
 *   validate prompt asks for) decides outright;
 * - legacy one-word "APPROVED" reviews (earlier phases, terse models) are
 *   still recognized;
 * - explicit negative language maps to CHANGES_NEEDED;
 * - anything else is INCONCLUSIVE — and AgentCore treats an inconclusive
 *   review as UNVERIFIED, never as success ("explicit uncertainty").
 */
object ReviewClassifier {

    enum class Verdict { APPROVED, CHANGES_NEEDED, INCONCLUSIVE }

    fun classify(review: String): Verdict {
        val text = review.trim()
        if (text.isEmpty()) return Verdict.INCONCLUSIVE

        // Phase-5 protocol: first "VERDICT:" marker decides.
        val verdict = Regex(
            pattern = "(?i)verdict\\s*[:\\-]\\s*(approved|changes[_ ]?needed|reject(?:ed)?|fail(?:ed|ure)?)",
        ).find(text)
        if (verdict != null) {
            return when (verdict.groupValues[1].lowercase()) {
                "approved" -> Verdict.APPROVED
                else -> Verdict.CHANGES_NEEDED
            }
        }

        val head = text.take(400)
        // Legacy protocol: a bare "APPROVED" anywhere near the top.
        if (Regex("(?i)\\bapproved\\b").containsMatchIn(head) &&
            !Regex("(?i)not\\s+approved").containsMatchIn(head)
        ) {
            return Verdict.APPROVED
        }
        // Explicit negative language without any approval marker.
        if (Regex("(?i)\\b(reject(?:ed)?|changes\\s+needed|must\\s+be\\s+fixed?|fails?|blocked)\\b")
            .containsMatchIn(head)
        ) {
            return Verdict.CHANGES_NEEDED
        }
        return Verdict.INCONCLUSIVE
    }
}
