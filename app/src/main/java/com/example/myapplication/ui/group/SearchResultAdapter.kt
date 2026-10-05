package com.example.myapplication.ui.group

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.databinding.ItemSearchResultBinding

class SearchResultAdapter(
    private val onAdd: (GroupMember) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private var items: List<GroupMember> = emptyList()
    private var query: String = ""

    fun submit(newItems: List<GroupMember>, newQuery: String) {
        items = newItems
        query = newQuery
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val member = items[position]
        val b = holder.binding
        b.tvResultName.text = highlight(member.name, query)
        b.tvResultEmail.text = member.email
        b.root.setOnClickListener { onAdd(member) }
    }

    /** Visual only: the stored name is untouched. The query is a prefix of the name. */
    private fun highlight(name: String, query: String): CharSequence {
        if (query.isEmpty() || !name.startsWith(query)) return name
        return SpannableString(name).apply {
            setSpan(ForegroundColorSpan(highlightColor), 0, query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private var highlightColor: Int = 0xFF1E6FD9.toInt()

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        highlightColor = ContextCompat.getColor(recyclerView.context, R.color.home_primary)
    }

    class VH(val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root)
}
