package com.example.myapplication.ui.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.ItemExpenseBinding
import com.example.myapplication.ui.home.formatShekel

class ExpenseAdapter : ListAdapter<ExpenseUi, ExpenseAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemExpenseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        b.tvExpenseDescription.text = item.description
        b.tvExpenseAmount.text = formatShekel(item.amount, withSign = false)
        b.tvExpensePayer.text = item.payerLabel
        b.tvExpenseDate.text = item.dateText
        b.tvExpenseDate.visibility = if (item.dateText == null) View.GONE else View.VISIBLE
        b.tvExpenseSplit.text = item.splitLabel
        b.tvExpenseSplit.visibility = if (item.splitLabel == null) View.GONE else View.VISIBLE
    }

    class VH(val binding: ItemExpenseBinding) : RecyclerView.ViewHolder(binding.root)

    private object Diff : DiffUtil.ItemCallback<ExpenseUi>() {
        override fun areItemsTheSame(a: ExpenseUi, b: ExpenseUi) = a.id == b.id
        override fun areContentsTheSame(a: ExpenseUi, b: ExpenseUi) = a == b
    }
}
