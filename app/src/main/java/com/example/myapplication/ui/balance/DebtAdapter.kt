package com.example.myapplication.ui.balance

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.databinding.ItemDebtBinding

class DebtAdapter(
    private val onDebtClick: (DebtUi) -> Unit = {},
    /** When set, an open debt the user owes shows the "I paid" button (the real payment-claim flow). */
    private val onClaimClick: ((DebtUi) -> Unit)? = null
) : ListAdapter<DebtUi, DebtAdapter.DebtViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        DebtViewHolder(ItemDebtBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: DebtViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, onClaimClick != null)
        holder.binding.btnClaim.setOnClickListener { onClaimClick?.invoke(item) }
        holder.itemView.setOnClickListener { onDebtClick(item) }
    }

    class DebtViewHolder(val binding: ItemDebtBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DebtUi, canClaim: Boolean) {
            val context = binding.root.context
            val name = item.otherName ?: "משתמש"
            binding.tvAvatar.text = item.otherName?.trim()?.firstOrNull()?.toString() ?: "?"
            binding.tvDebtTitle.text = if (item.owedToMe) "$name חייב/ת לך" else "את חייבת ל$name"
            binding.tvDebtGroup.text = listOfNotNull(item.groupIcon, item.groupName).joinToString(" ")
            binding.tvDebtAmount.text = formatMoney(item.amount)
            binding.tvDebtStatus.visibility = if (item.claimPending) View.VISIBLE else View.GONE
            binding.tvDebtStatus.text = if (item.owedToMe) "ממתין לאישורך" else "ממתין לאישור"
            binding.btnClaim.visibility =
                if (canClaim && !item.owedToMe && !item.claimPending) View.VISIBLE else View.GONE
            binding.tvDebtAmount.setTextColor(
                ContextCompat.getColor(context, if (item.owedToMe) R.color.home_positive else R.color.home_negative)
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<DebtUi>() {
        override fun areItemsTheSame(a: DebtUi, b: DebtUi) = a.key == b.key
        override fun areContentsTheSame(a: DebtUi, b: DebtUi) = a == b
    }
}
