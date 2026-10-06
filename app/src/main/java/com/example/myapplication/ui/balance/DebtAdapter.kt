package com.example.myapplication.ui.balance

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.databinding.ItemDebtBinding

class DebtAdapter(
    private val onDebtClick: (DebtUi) -> Unit = {}
) : ListAdapter<DebtUi, DebtAdapter.DebtViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        DebtViewHolder(ItemDebtBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: DebtViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener { onDebtClick(item) }
    }

    class DebtViewHolder(private val binding: ItemDebtBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DebtUi) {
            val context = binding.root.context
            val name = item.otherName ?: "משתמש"
            binding.tvAvatar.text = item.otherName?.trim()?.firstOrNull()?.toString() ?: "?"
            binding.tvDebtTitle.text = if (item.owedToMe) "$name חייב/ת לך" else "את חייבת ל$name"
            binding.tvDebtGroup.text = item.groupName
            binding.tvDebtAmount.text = formatMoney(item.amount)
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
