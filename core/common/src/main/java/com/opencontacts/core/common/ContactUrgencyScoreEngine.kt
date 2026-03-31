package com.opencontacts.core.common

data class ContactUrgencyScore(
    val score: Int,
    val headline: String,
    val hints: List<String>,
    val bestTimeLabel: String? = null,
)

data class ContactUrgencyHistoryItem(
    val timestamp: Long,
    val visualType: ContactUrgencyVisualType,
)

enum class ContactUrgencyVisualType {
    OUTGOING_ANSWERED,
    OUTGOING_NOT_ANSWERED,
    OUTGOING_REJECTED,
    INCOMING_ANSWERED,
    INCOMING_CANCELED,
    MISSED,
}

object ContactUrgencyScoreEngine {
    fun evaluate(
        history: List<ContactUrgencyHistoryItem>,
        now: Long = System.currentTimeMillis(),
    ): ContactUrgencyScore {
        if (history.isEmpty()) return ContactUrgencyScore(0, "Normal", emptyList(), null)

        var score = 0
        val hints = mutableListOf<String>()

        val lastMissed = history.firstOrNull { it.visualType == ContactUrgencyVisualType.MISSED }
        if (lastMissed != null && now - lastMissed.timestamp <= 3L * 24 * 60 * 60 * 1000) {
            score += 30
            hints += "Missed you recently"
        }

        val lastOutgoing = history.firstOrNull {
            it.visualType == ContactUrgencyVisualType.OUTGOING_ANSWERED ||
                it.visualType == ContactUrgencyVisualType.OUTGOING_NOT_ANSWERED ||
                it.visualType == ContactUrgencyVisualType.OUTGOING_REJECTED
        }
        if (lastOutgoing != null) {
            val days = ((now - lastOutgoing.timestamp) / (24L * 60 * 60 * 1000)).toInt()
            if (days >= 12) {
                score += 20
                hints += "You haven't called in $days days"
            }
        }

        val answered = history.count {
            it.visualType == ContactUrgencyVisualType.INCOMING_ANSWERED ||
                it.visualType == ContactUrgencyVisualType.OUTGOING_ANSWERED
        }
        val total = history.size.coerceAtLeast(1)
        val responseRate = answered.toFloat() / total
        if (responseRate >= 0.6f && total >= 4) {
            score += 20
            hints += "High response rate"
        }

        val nightCalls = history.filter {
            val hour = ((it.timestamp / 1000 / 60 / 60) % 24).toInt()
            hour in 20..23 || hour in 0..5
        }
        val best = if (nightCalls.size >= (history.size.coerceAtLeast(1) / 2)) "Night" else "Day"
        if (best == "Night") {
            score += 10
            hints += "This contact often answers at night"
        }

        val headline = when {
            score >= 50 -> "High urgency"
            score >= 25 -> "Worth following up"
            else -> "Normal"
        }
        return ContactUrgencyScore(score, headline, hints.distinct(), best)
    }
}
