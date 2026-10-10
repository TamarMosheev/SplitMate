package com.example.myapplication.ui.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.databinding.ItemSearchResultBinding

/** Search results for "add member": each row is addable, already in the group, or being added. */
class AddMemberAdapter(
    private val onAdd: (GroupMember) -> Unit
) : RecyclerView.Adapter<AddMemberAdapter.VH>() {

    private var items: List<GroupMember> = emptyList()
    private var memberIds: Set<String> = emptySet()
    private var addingUid: String? = null

    fun submit(newItems: List<GroupMember>, members: Set<String>, adding: String?) {
        items = newItems
        memberIds = members
        addingUid = adding
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val b = holder.binding
        val ctx = b.root.context
        b.tvResultName.text = m.name.ifBlank { "משתמש" }
        b.tvResultEmail.text = m.email

        val already = m.uid in memberIds
        val adding = addingUid == m.uid
        b.tvResultAdd.text = when {
            already -> "כבר בקבוצה"
            adding -> "מוסיף..."
            else -> "הוספה"
        }
        val enabled = !already && addingUid == null
        b.tvResultAdd.setTextColor(ContextCompat.getColor(ctx, if (already) R.color.home_muted else R.color.home_primary))
        b.tvResultAdd.alpha = if (enabled || adding) 1f else 0.5f
        val click = if (enabled) View.OnClickListener { onAdd(m) } else null
        b.root.isClickable = enabled
        b.root.setOnClickListener(click)
        b.tvResultAdd.setOnClickListener(click)
    }

    class VH(val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root)
}
