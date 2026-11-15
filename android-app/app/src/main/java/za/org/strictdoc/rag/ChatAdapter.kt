package za.org.strictdoc.rag

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class CitationSourceItem(
    val chunkId: String,
    val filename: String,
    val pageNumber: Int,
    val sectionPath: String,
    val score: Double,
    val excerpt: String
)

data class WebSourceItem(val title: String, val url: String)

data class ChatTurn(
    val id: String,
    val query: String,
    var status: String, // LOADING | ANSWERED | INSUFFICIENT_EVIDENCE | EXTERNAL_ENRICHED | ERROR
    var docBody: String = "",
    var sources: List<CitationSourceItem> = emptyList(),
    var canApproveExternal: Boolean = false,
    var anchorTopic: String = "",
    var webContext: String = "",
    var webSources: List<WebSourceItem> = emptyList(),
    var aiPoints: List<String> = emptyList(),
    var usedExternal: Boolean = false
) {
    fun toOfflineSummary(): String {
        val sb = StringBuilder()
        sb.append("[FROM YOUR DOCUMENTS]\n").append(docBody).append("\n")
        if (webContext.isNotEmpty()) {
            sb.append("\n[APPROVED LIVE WEB]\n").append(webContext).append("\n")
            webSources.forEach { sb.append("↗ ${it.title}: ${it.url}\n") }
        }
        if (aiPoints.isNotEmpty()) {
            sb.append("\n[APPROVED AI KNOWLEDGE]\n")
            aiPoints.forEach { sb.append("• $it\n") }
        }
        return sb.toString()
    }
}

class ChatAdapter(
    private val items: List<ChatTurn>,
    private val onCitationClick: (CitationSourceItem) -> Unit,
    private val onWebLinkClick: (String) -> Unit,
    private val onApproveExternal: (ChatTurn) -> Unit,
    private val onSaveTurn: (ChatTurn) -> Unit
) : RecyclerView.Adapter<ChatAdapter.TurnVH>() {

    class TurnVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvUserQuery: TextView = v.findViewById(R.id.tvUserQuery)
        val tvTurnBadge: TextView = v.findViewById(R.id.tvTurnBadge)
        val btnSaveTurn: Button = v.findViewById(R.id.btnSaveTurn)
        val tvDocClaims: TextView = v.findViewById(R.id.tvDocClaims)
        val llCitationChips: LinearLayout = v.findViewById(R.id.llCitationChips)
        val llWebSection: LinearLayout = v.findViewById(R.id.llWebSection)
        val tvWebText: TextView = v.findViewById(R.id.tvWebText)
        val llWebLinks: LinearLayout = v.findViewById(R.id.llWebLinks)
        val llAiSection: LinearLayout = v.findViewById(R.id.llAiSection)
        val tvAiPoints: TextView = v.findViewById(R.id.tvAiPoints)
        val btnUnlockExternal: Button = v.findViewById(R.id.btnUnlockExternal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TurnVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_turn, parent, false)
        return TurnVH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: TurnVH, position: Int) {
        val turn = items[position]
        val ctx = h.itemView.context

        h.tvUserQuery.text = turn.query
        h.tvDocClaims.text = turn.docBody

        when (turn.status) {
            "LOADING" -> {
                h.tvTurnBadge.text = if (turn.usedExternal) "FETCHING ALIGNED EXTERNAL..." else "CHECKING YOUR VAULT..."
                h.tvTurnBadge.setTextColor(Color.parseColor("#38BDF8"))
                h.btnSaveTurn.visibility = View.GONE
            }
            "ANSWERED" -> {
                h.tvTurnBadge.text = if (turn.usedExternal) "VERIFIED DOCS + APPROVED EXTERNAL" else "STRICT MODE • VERIFIED FROM DOCS"
                h.tvTurnBadge.setTextColor(Color.parseColor("#34D399"))
                h.btnSaveTurn.visibility = View.VISIBLE
            }
            "EXTERNAL_ENRICHED" -> {
                h.tvTurnBadge.text = "APPROVED EXTERNAL • ALIGNED TO VAULT"
                h.tvTurnBadge.setTextColor(Color.parseColor("#C084FC"))
                h.btnSaveTurn.visibility = View.VISIBLE
            }
            "INSUFFICIENT_EVIDENCE" -> {
                h.tvTurnBadge.text = "STRICT STOP • NOT IN DOCUMENTS"
                h.tvTurnBadge.setTextColor(Color.parseColor("#FBBF24"))
                h.btnSaveTurn.visibility = View.VISIBLE
            }
            else -> {
                h.tvTurnBadge.text = "CONNECTION ERROR"
                h.tvTurnBadge.setTextColor(Color.parseColor("#F87171"))
                h.btnSaveTurn.visibility = View.GONE
            }
        }

        h.btnSaveTurn.setOnClickListener { onSaveTurn(turn) }

        // Tappable Citation Chips
        h.llCitationChips.removeAllViews()
        turn.sources.forEach { src ->
            val chip = TextView(ctx).apply {
                text = "${src.filename.take(18)} • p.${src.pageNumber}"
                setTextColor(Color.parseColor("#6EE7B7"))
                textSize = 11f
                setBackgroundResource(R.drawable.bg_chip_cite)
                setPadding(22, 10, 22, 10)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 12 }
                layoutParams = lp
                setOnClickListener { onCitationClick(src) }
            }
            h.llCitationChips.addView(chip)
        }

        // Layer 2: Clickable Web Context
        if (turn.webContext.isNotEmpty()) {
            h.llWebSection.visibility = View.VISIBLE
            h.tvWebText.text = turn.webContext
            h.llWebLinks.removeAllViews()
            turn.webSources.forEach { ws ->
                val linkView = TextView(ctx).apply {
                    text = "↗ ${ws.title.ifEmpty { ws.url }}"
                    setTextColor(Color.parseColor("#93C5FD"))
                    textSize = 12f
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setPadding(0, 6, 0, 6)
                    setOnClickListener { onWebLinkClick(ws.url) }
                }
                h.llWebLinks.addView(linkView)
            }
        } else {
            h.llWebSection.visibility = View.GONE
        }

        // Layer 3: Aligned AI Knowledge
        if (turn.aiPoints.isNotEmpty()) {
            h.llAiSection.visibility = View.VISIBLE
            h.tvAiPoints.text = turn.aiPoints.joinToString("\n") { "• $it" }
        } else {
            h.llAiSection.visibility = View.GONE
        }

        // Approval Gate
        if (turn.canApproveExternal && turn.status != "LOADING") {
            h.btnUnlockExternal.visibility = View.VISIBLE
            h.btnUnlockExternal.text = "Approve Aligned Web + AI (${turn.anchorTopic.take(28)})"
            h.btnUnlockExternal.setOnClickListener { onApproveExternal(turn) }
        } else {
            h.btnUnlockExternal.visibility = View.GONE
        }
    }
}