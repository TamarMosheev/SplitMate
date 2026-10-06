package com.example.myapplication.ui.balance

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.databinding.ItemGroupBalanceBinding
import com.example.myapplication.ui.group.bindGroupDeleteAction

class GroupBalanceAdapter(
    private val onGroupClick: (GroupBalanceUi) -> Unit = {},
    private val onDeleteClick: (GroupBalanceUi) -> Unit = {}
) : ListAdapter<GroupBalanceUi, GroupBalanceAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemGroupBalanceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener { onGroupClick(item) }
        // Own tap target: asks for confirmation, never opens the group.
        holder.deleteView.setOnClickListener { onDeleteClick(item) }
    }

    class VH(private val binding: ItemGroupBalanceBinding) : RecyclerView.ViewHolder(binding.root) {
        val deleteView: View get() = binding.ivDeleteGroup

        fun bind(item: GroupBalanceUi) {
            val context = binding.root.context
            // Shared rule, applied explicitly on EVERY bind.
            bindGroupDeleteAction(
                "MyBalance", "GroupBalanceAdapter(item_group_balance)", item.id, item.name, item.createdBy,
                binding.ivDeleteGroup, item.isDeleting
            )
            binding.root.alpha = if (item.isDeleting) 0.5f else 1f
            binding.tvGroupName.text = item.name
            binding.tvGroupIcon.text = item.icon
            binding.tvGroupIcon.visibility = if (item.icon.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.ivGroupFallback.visibility = if (item.icon.isNullOrBlank()) View.VISIBLE else View.GONE

            val balance = item.balance
            val (text, color) = when {
                balance == null -> "לא זמין" to R.color.home_muted
                isSettled(balance) -> "מסודר · ${formatMoney(balance)}" to R.color.home_muted
                balance.signum() > 0 -> formatSignedMoney(balance) to R.color.home_positive
                else -> formatSignedMoney(balance) to R.color.home_negative
            }
            binding.tvGroupBalance.text = text
            binding.tvGroupBalance.setTextColor(ContextCompat.getColor(context, color))
        }
    }

    private object Diff : DiffUtil.ItemCallback<GroupBalanceUi>() {
        override fun areItemsTheSame(a: GroupBalanceUi, b: GroupBalanceUi) = a.id == b.id
        override fun areContentsTheSame(a: GroupBalanceUi, b: GroupBalanceUi) = a == b
    }
}
