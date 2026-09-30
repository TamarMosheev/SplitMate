package com.example.myapplication.ui.home

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.databinding.ItemGroupBinding
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

private val amountFormat = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))

fun formatShekel(amount: Double, withSign: Boolean): String {
    val sign = when {
        !withSign -> ""
        amount < 0 -> "-"
        else -> "+"
    }
    return "$sign${amountFormat.format(abs(amount))} ₪"
}

class GroupAdapter : ListAdapter<GroupItemUi, GroupAdapter.GroupViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder =
        GroupViewHolder(ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) =
        holder.bind(getItem(position))

    class GroupViewHolder(private val binding: ItemGroupBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GroupItemUi) {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density
            val size = (32 * density).toInt()
            val overlap = (8 * density).toInt()

            binding.tvGroupName.text = item.name

            binding.membersRow.removeAllViews()
            item.members.forEachIndexed { index, member ->
                val params = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = -overlap
                }
                binding.membersRow.addView(avatar(member, size), params)
            }

            val positive = item.personalBalance >= 0
            binding.tvPersonalBalance.text = formatShekel(item.personalBalance, withSign = true)
            binding.tvPersonalBalance.setTextColor(
                ContextCompat.getColor(context, if (positive) R.color.home_positive else R.color.home_negative)
            )
            binding.tvPersonalBalance.setBackgroundResource(
                if (positive) R.drawable.bg_badge_positive else R.drawable.bg_badge_negative
            )
        }

        private fun avatar(member: GroupMemberUi, size: Int): View {
            val context = binding.root.context
            val initial = member.displayName?.trim()?.firstOrNull()?.toString()
            return if (initial != null) {
                TextView(context).apply {
                    text = initial
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.home_deep))
                    setBackgroundResource(R.drawable.bg_avatar)
                }
            } else {
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_person)
                    setPadding(size / 5, size / 5, size / 5, size / 5)
                    setBackgroundResource(R.drawable.bg_avatar)
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<GroupItemUi>() {
        override fun areItemsTheSame(a: GroupItemUi, b: GroupItemUi) = a.id == b.id
        override fun areContentsTheSame(a: GroupItemUi, b: GroupItemUi) = a == b
    }
}
