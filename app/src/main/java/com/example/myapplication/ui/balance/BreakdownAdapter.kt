package com.example.myapplication.ui.balance

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.ItemBreakdownBinding

/** Rows of "ממה זה מורכב", shown exactly as the server returned them. */
class BreakdownAdapter(
    private val onExpenseClick: (expenseId: String) -> Unit = {}
) : ListAdapter<BreakdownRowUi, BreakdownAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBreakdownBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.bind(row, isLast = position == itemCount - 1)
        // Expense rows open the one Expense Details screen; payment rows are not expenses.
        val expenseId = row.expenseId
        if (expenseId != null) holder.itemView.setOnClickListener { onExpenseClick(expenseId) }
        else holder.itemView.setOnClickListener(null).also { holder.itemView.isClickable = false }
    }

    class VH(private val binding: ItemBreakdownBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BreakdownRowUi, isLast: Boolean) {
            binding.tvRowTitle.text = row.title
            binding.tvRowSubtitle.text = row.subtitle
            binding.tvRowSubtitle.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
            // Negative rows reduce the debt: keep the sign, e.g. "-₪30.00".
            binding.tvRowAmount.text =
                if (row.amount.signum() < 0) formatSignedMoney(row.amount) else formatMoney(row.amount)
            binding.divider.visibility = if (isLast) View.GONE else View.VISIBLE
        }
    }

    private object Diff : DiffUtil.ItemCallback<BreakdownRowUi>() {
        override fun areItemsTheSame(a: BreakdownRowUi, b: BreakdownRowUi) = a.key == b.key
        override fun areContentsTheSame(a: BreakdownRowUi, b: BreakdownRowUi) = a == b
    }
}
