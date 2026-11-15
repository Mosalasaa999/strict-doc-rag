package za.org.strictdoc.rag

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.org.strictdoc.rag.data.DocumentRecord

class VaultAdapter(
    private val docs: List<DocumentRecord>,
    private val selectedRemoteIds: MutableSet<String>,
    private val selectAllMode: () -> Boolean,
    private val onToggleChanged: () -> Unit
) : RecyclerView.Adapter<VaultAdapter.DocVH>() {

    class DocVH(v: View) : RecyclerView.ViewHolder(v) {
        val cbScope: CheckBox = v.findViewById(R.id.cbDocScope)
        val tvName: TextView = v.findViewById(R.id.tvDocName)
        val tvMeta: TextView = v.findViewById(R.id.tvDocMeta)
        val tvBadge: TextView = v.findViewById(R.id.tvDocBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_vault_doc, parent, false)
        return DocVH(v)
    }

    override fun getItemCount(): Int = docs.size

    override fun onBindViewHolder(h: DocVH, pos: Int) {
        val d = docs[pos]
        h.tvName.text = d.filename
        h.tvMeta.text = if (d.chunkCount > 0) "${d.chunkCount} indexed sections" else "Waiting for cloud sync"
        h.tvBadge.text = d.syncState
        h.tvBadge.setTextColor(
            Color.parseColor(
                when (d.syncState) {
                    "SYNCED" -> "#34D399"
                    "FAILED" -> "#F87171"
                    else -> "#FBBF24"
                }
            )
        )

        h.cbScope.setOnCheckedChangeListener(null)
        val canScope = d.syncState == "SYNCED" && d.remoteDocId.isNotEmpty()
        h.cbScope.isEnabled = canScope
        h.cbScope.isChecked = canScope && (selectAllMode() || selectedRemoteIds.contains(d.remoteDocId))

        h.cbScope.setOnCheckedChangeListener { _, isChecked ->
            if (!canScope) return@setOnCheckedChangeListener
            if (isChecked) selectedRemoteIds.add(d.remoteDocId) else selectedRemoteIds.remove(d.remoteDocId)
            onToggleChanged()
        }
    }
}