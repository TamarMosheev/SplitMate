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

/** Rows of direct debts. They carry no settlementId, so a tap opens Debt Details by person, not by settlement. */
class DirectDebtAdapter(
    private val onDebtClick: (DirectDebtUi) -> Unit = {}
) : ListAdapter<DirectDebtUi, DirectDebtAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemDebtBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position), onDebtClick)

    class Holder(private val binding: ItemDebtBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DirectDebtUi, onDebtClick: (DirectDebtUi) -> Unit) {
            val name = item.otherName ?: "משתמש"
            binding.ivAvatar.setAvatar(item.otherAvatar)
            binding.tvDebtTitle.text = if (item.owedToMe) "$name חייב/ת לך" else "את חייבת ל$name"
            // One group: its name. Several: each with its own amount, as the backend split it.
            binding.tvDebtGroup.text =
                if (item.groups.size <= 1) item.groups.firstOrNull()?.name.orEmpty()
                else item.groups.joinToString(" · ") { "${it.name} ${formatMoney(it.amount)}" }
            binding.tvDebtAmount.text = formatMoney(item.amount)
            binding.tvDebtAmount.setTextColor(
                ContextCompat.getColor(
                    binding.root.context, if (item.owedToMe) R.color.home_positive else R.color.home_negative
                )
            )
            binding.tvDebtStatus.visibility = View.GONE
            binding.btnClaim.visibility = View.GONE
            binding.root.setOnClickListener { onDebtClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DirectDebtUi>() {
        override fun areItemsTheSame(a: DirectDebtUi, b: DirectDebtUi) = a.otherUid == b.otherUid && a.owedToMe == b.owedToMe
        override fun areContentsTheSame(a: DirectDebtUi, b: DirectDebtUi) = a == b
    }
}
