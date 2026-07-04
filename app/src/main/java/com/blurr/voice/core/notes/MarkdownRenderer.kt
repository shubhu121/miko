package com.blurr.voice.core.notes

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.image.ImagesPlugin

/**
 * Renders the notepad's markdown into a [TextView]: bold, italic, strikethrough, inline &
 * fenced code (monospace), lists, headings and local images — plus two extras Markwon lacks
 * out of the box: `==highlight==` and lightweight code syntax coloring.
 */
object MarkdownRenderer {

    // Syntax-highlight palette (aligned with Miko's).
    private const val KEYWORD = 0xFFB084F5.toInt()
    private const val STRING = 0xFF7FD1A6.toInt()
    private const val COMMENT = 0xFF6E6A80.toInt()
    private const val NUMBER = 0xFFF5C784.toInt()
    private const val HIGHLIGHT_BG = 0x66F5C784

    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "for", "while",
        "return", "import", "package", "public", "private", "protected", "def", "function",
        "const", "let", "void", "true", "false", "null", "new", "this", "when", "is", "in",
        "override", "suspend", "data", "sealed", "enum", "try", "catch", "finally", "throw",
        "async", "await", "static", "final", "extends", "implements", "int", "float", "double",
        "boolean", "string", "print", "println", "echo", "func", "struct", "type", "map", "list"
    )

    private val keywordRegex = Regex("\\b(${KEYWORDS.joinToString("|")})\\b")
    private val commentRegex = Regex("(//[^\\n]*)|(#[^\\n]*)|(/\\*.*?\\*/)", RegexOption.DOT_MATCHES_ALL)
    private val stringRegex = Regex("\"[^\"\\n]*\"|'[^'\\n]*'")
    private val numberRegex = Regex("\\b\\d+(\\.\\d+)?\\b")
    private val highlightRegex = Regex("==(.+?)==")

    @Volatile private var instance: Markwon? = null

    private fun markwon(context: Context): Markwon =
        instance ?: synchronized(this) {
            instance ?: Markwon.builder(context.applicationContext)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(ImagesPlugin.create())
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun afterSetText(textView: TextView) {
                        colorizeCode(textView)
                        applyHighlights(textView)
                    }
                })
                .build()
                .also { instance = it }
        }

    fun render(textView: TextView, markdown: String) {
        markwon(textView.context).setMarkdown(textView, markdown)
    }

    /** Adds token colors inside Markwon's code spans. */
    private fun colorizeCode(textView: TextView) {
        val sp = textView.text as? Spannable ?: return
        val ranges = mutableListOf<IntRange>()
        sp.getSpans(0, sp.length, CodeBlockSpan::class.java)
            .forEach { ranges.add(sp.getSpanStart(it)..sp.getSpanEnd(it)) }
        sp.getSpans(0, sp.length, CodeSpan::class.java)
            .forEach { ranges.add(sp.getSpanStart(it)..sp.getSpanEnd(it)) }

        for (range in ranges) {
            val start = range.first
            val end = range.last
            if (start < 0 || end > sp.length || start >= end) continue
            val code = sp.subSequence(start, end).toString()
            fun paint(regex: Regex, color: Int) {
                regex.findAll(code).forEach { m ->
                    sp.setSpan(
                        ForegroundColorSpan(color),
                        start + m.range.first, start + m.range.last + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            paint(keywordRegex, KEYWORD)
            paint(numberRegex, NUMBER)
            paint(stringRegex, STRING)
            paint(commentRegex, COMMENT) // last so comments win
        }
    }

    /** Replaces `==text==` with a highlighted background span. */
    private fun applyHighlights(textView: TextView) {
        val current = textView.text ?: return
        if (!current.contains("==")) return
        val ssb = SpannableStringBuilder(current)
        // Work from the end so earlier match offsets stay valid as we delete markers.
        highlightRegex.findAll(ssb.toString()).toList().asReversed().forEach { m ->
            val full = m.range
            val innerStart = full.first + 2
            val innerEnd = full.last - 1 // exclusive-ish
            // delete trailing '==' then leading '==', then highlight the inner text
            ssb.delete(full.last - 1, full.last + 1)
            ssb.delete(full.first, full.first + 2)
            val hlStart = full.first
            val hlEnd = hlStart + (innerEnd - innerStart + 1)
            if (hlStart in 0..ssb.length && hlEnd in hlStart..ssb.length) {
                ssb.setSpan(BackgroundColorSpan(HIGHLIGHT_BG), hlStart, hlEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        textView.text = ssb
    }
}
