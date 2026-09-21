package org.tomsense.data

import org.tomsense.db.Conversation
import org.tomsense.db.Message

/**
 * Render a conversation as markdown (migration doc §3, "Export").
 *
 * Deliberately a pure function over rows the caller already has: export works
 * offline, needs no network and no platform APIs, and can be tested without a
 * device. Stable had to serve this from an endpoint because the server owned
 * the history; here the phone already has it.
 */
fun exportMarkdown(conversation: Conversation, messages: List<Message>): String {
    val sb = StringBuilder()

    val title = conversation.title.ifBlank { "Conversation" }
    sb.append("# ").append(title).append("\n\n")
    if (conversation.model.isNotBlank()) {
        sb.append("*Model: ").append(conversation.model).append("*\n\n")
    }

    for (msg in messages) {
        // Tool turns are protocol, not conversation. They carry raw JSON
        // payloads that are noise in a document meant to be read, and an
        // export that dumps them is one nobody can paste anywhere.
        if (msg.role == "tool") continue
        // An answer that was never generated (a stopped or failed turn) would
        // otherwise export as a heading with nothing under it.
        if (msg.content.isBlank()) continue

        sb.append("## ").append(roleLabel(msg.role)).append("\n\n")
        sb.append(msg.content.trim()).append("\n\n")
    }

    return sb.toString().trimEnd() + "\n"
}

private fun roleLabel(role: String): String = when (role) {
    "user" -> "You"
    "assistant" -> "TomSense"
    "system" -> "System"
    else -> role.replaceFirstChar { it.uppercase() }
}

/**
 * A filename-safe slug for the export.
 *
 * Collapses anything that is not alphanumeric, because this string reaches
 * real filesystems and content providers where a `/` or a `:` in a name is
 * either an error or a path traversal.
 */
fun exportFileName(conversation: Conversation): String {
    val slug = conversation.title
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .take(60)
    return if (slug.isEmpty()) "conversation.md" else "$slug.md"
}
