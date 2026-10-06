package com.example.myapplication.ui.notifications

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.databinding.ItemNotificationBinding
import com.example.myapplication.ui.balance.formatMoney

class NotificationAdapter(
    private val onClick: (NotificationUi) -> Unit,
    private val onCheckClick: (NotificationUi) -> Unit
) : ListAdapter<NotificationUi, NotificationAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener { onClick(item) }
        // Separate target: marks as read only, never opens the debt.
        holder.checkView.setOnClickListener {
            Log.d("NotificationAdapter", "checkmark clicked notificationId=${item.id} isRead=${item.isRead}")
            onCheckClick(item)
        }
    }

    class VH(private val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root) {
        val checkView: View get() = binding.ivCheck

        fun bind(item: NotificationUi) {
            val context = binding.root.context
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(context, if (item.isRead) R.color.white else R.color.home_light_surface)
            )
            binding.ivCheck.setImageResource(if (item.isRead) R.drawable.ic_check_on else R.drawable.ic_check_off)
            binding.ivCheck.contentDescription = if (item.isRead) "נקרא" else "סמן כנקרא"
            // One mapping for every row, every user, every group: the icon depends only on the notification type.
            val style = NotificationStyle.of(item.type)
            binding.ivType.setImageResource(style.iconRes)
            ImageViewCompat.setImageTintList(
                binding.ivType, ColorStateList.valueOf(ContextCompat.getColor(context, style.tintRes))
            )
            // Read rows are softer; unread rows are bold.
            binding.root.alpha = if (item.isRead) 0.75f else 1f
            binding.tvSender.text = item.senderName ?: "משתמש"
            binding.tvSender.setTypeface(null, if (item.isRead) Typeface.NORMAL else Typeface.BOLD)
            binding.tvMessage.text = item.message
            binding.tvMessage.setTypeface(null, if (item.isRead) Typeface.NORMAL else Typeface.BOLD)
            binding.tvAmount.text = item.amount?.let { formatMoney(it) }
            binding.tvAmount.visibility = if (item.amount == null) View.GONE else View.VISIBLE
            binding.tvMeta.text = listOfNotNull(item.groupName, item.createdAtText).joinToString(" · ")
            binding.tvMeta.visibility = if (binding.tvMeta.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private object Diff : DiffUtil.ItemCallback<NotificationUi>() {
        override fun areItemsTheSame(a: NotificationUi, b: NotificationUi) = a.id == b.id
        override fun areContentsTheSame(a: NotificationUi, b: NotificationUi) = a == b
    }
}
