package nl.wiebenieuwenhuis.tender.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.arr
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.jsonOf
import nl.wiebenieuwenhuis.tender.api.str
import nl.wiebenieuwenhuis.tender.app.AppModel

/** A line of the conversation as shown: a question, an answer, or the tools the model used. */
data class ChatLine(val id: Int, val kind: Kind, val text: String) {
    enum class Kind { User, Assistant, Tools }
}

/**
 * One conversation with Frigate's GenAI chat on a server. Frigate returns the whole conversation
 * (with its system prompt and tool calls) after each answer; it is sent back as is on the next
 * question so the model keeps its context. It runs in the app's scope, so an answer keeps
 * arriving while another screen is open.
 */
class ChatSession {
    val chain = mutableStateListOf<JsonElement>()
    /** Answer text arriving while Frigate streams. */
    var streaming by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null

    val isEmpty: Boolean get() = lines.isEmpty() && !busy

    /** What to show: questions, answers and which tools the model used. */
    val lines: List<ChatLine>
        get() {
            val out = mutableListOf<ChatLine>()
            chain.forEachIndexed { i, msg ->
                val content = msg["content"].str() ?: ""
                when (msg["role"].str()) {
                    // Frigate adds camera frames for the model as user messages without text; skip those.
                    "user" -> if (content.isNotBlank()) out.add(ChatLine(i * 1000, ChatLine.Kind.User, content))
                    "assistant" -> {
                        val names = msg["tool_calls"].arr()?.mapNotNull { it["function"]["name"].str() } ?: emptyList()
                        if (names.isNotEmpty()) {
                            // "Get live context ×7" instead of the same name seven times.
                            val counts = names.groupingBy { it }.eachCount()
                            val text = names.distinct().joinToString(", ") { n ->
                                val c = counts[n] ?: 1
                                if (c > 1) "${toolName(n)} ×$c" else toolName(n)
                            }
                            out.add(ChatLine(i * 1000, ChatLine.Kind.Tools, text))
                        }
                        if (content.isNotBlank()) out.add(ChatLine(i * 1000 + 1, ChatLine.Kind.Assistant, content))
                    }
                }
            }
            return out
        }

    fun send(text: String, client: FrigateClient) {
        val question = text.trim()
        if (question.isEmpty() || busy) return
        chain.add(jsonOf(mapOf("role" to "user", "content" to question)))
        val messages = chain.toList()
        busy = true
        error = null
        streaming = ""
        job = AppModel.scope.launch {
            try {
                client.chat(messages).collect { event ->
                    when (event["type"].str()) {
                        "content" -> streaming += event["delta"].str() ?: ""
                        "messages" -> event["messages"].arr()?.let { list ->
                            chain.clear(); chain.addAll(list); streaming = ""
                        }
                        "error" -> error = event["error"].str() ?: L(R.string.chat_something_wrong)
                    }
                }
            } catch (e: CancellationException) {
                // Stopped by the user.
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            // Keep an answer that streamed but never came back in the conversation.
            if (streaming.isNotEmpty()) {
                chain.add(jsonOf(mapOf("role" to "assistant", "content" to streaming)))
                streaming = ""
            }
            busy = false
        }
    }

    fun stop() { job?.cancel() }

    fun reset() {
        job?.cancel()
        chain.clear()
        streaming = ""
        error = null
        busy = false
    }

    companion object {
        fun toolName(raw: String): String = raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** The conversations per server and the server picked, kept while switching screens. */
object ChatSessions {
    private val sessions = mutableMapOf<String, ChatSession>()
    var serverId by mutableStateOf<String?>(null)

    fun session(serverId: String): ChatSession = sessions.getOrPut(serverId) { ChatSession() }
}

/** Markdown from the model: bold, italics and code inline; lists and headings turned into plain lines. */
fun chatMarkdown(text: String): AnnotatedString {
    val lines = text.split("\n").map { raw ->
        val trimmed = raw.trimStart()
        val indent = (raw.length - trimmed.length) / 2
        when {
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> "  ".repeat(indent) + "• " + trimmed.drop(2)
            trimmed.startsWith("#") -> "**" + trimmed.trimStart('#').trim() + "**"
            else -> raw
        }
    }
    val s = lines.joinToString("\n")
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val rest = s.substring(i)
            fun span(marker: String, style: SpanStyle): Boolean {
                if (!rest.startsWith(marker)) return false
                val end = s.indexOf(marker, i + marker.length)
                if (end < 0 || end == i + marker.length || s.substring(i + marker.length, end).contains('\n')) return false
                withStyle(style) { append(s.substring(i + marker.length, end)) }
                i = end + marker.length
                return true
            }
            if (span("**", SpanStyle(fontWeight = FontWeight.Bold))) continue
            if (span("__", SpanStyle(fontWeight = FontWeight.Bold))) continue
            if (span("`", SpanStyle(fontFamily = FontFamily.Monospace))) continue
            if (span("*", SpanStyle(fontStyle = FontStyle.Italic))) continue
            append(s[i])
            i++
        }
    }
}
