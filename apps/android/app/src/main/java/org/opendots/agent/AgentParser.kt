package org.opendots.agent

data class SendMessageDraft(val recipient: String, val body: String)

object AgentParser {
    private val openPatterns = listOf(
        Regex("""(?i)^\s*(?:open|launch|start)\s+(.+?)[.!]?\s*$"""),
        Regex("""(?i)^\s*(.+?)\s+(?:open|launch)\s+(?:kar|karo|kr|kro)\s*$"""),
        Regex("""^\s*(.+?)\s+खोल(?:ो|ना)?\s*$""")
    )

    fun openAppTarget(input: String): String? {
        for (pattern in openPatterns) {
            val match = pattern.find(input)
            if (match != null) return match.groupValues[1].trim()
        }
        return null
    }

    fun memoryText(input: String): String? {
        val patterns = listOf(
            Regex("""(?i)^\s*remember\s+(?:that\s+)?(.+)$"""),
            Regex("""(?i)^\s*yaad\s+rakh(?:o)?\s+(.+)$""")
        )
        return patterns.firstNotNullOfOrNull { it.find(input)?.groupValues?.getOrNull(1)?.trim() }
    }

    fun sendMessageDraft(input: String): SendMessageDraft? {
        val match = Regex("""(?i)^\s*(?:message|text)\s+(.+?)\s+(?:that|saying)\s+(.+)$""").find(input)
            ?: return null
        return SendMessageDraft(match.groupValues[1].trim(), match.groupValues[2].trim())
    }
}
