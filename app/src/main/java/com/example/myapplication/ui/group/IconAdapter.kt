package com.example.myapplication.ui.group

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.databinding.ItemIconBinding

class IconAdapter(private val onSelected: (String) -> Unit) : RecyclerView.Adapter<IconAdapter.VH>() {

    var selected: String = ""
        set(value) {
            if (field == value) return
            val old = GROUP_ICONS.indexOf(field)
            field = value
            if (old >= 0) notifyItemChanged(old)
            val new = GROUP_ICONS.indexOf(value)
            if (new >= 0) notifyItemChanged(new)
        }

    override fun getItemCount() = GROUP_ICONS.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemIconBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val icon = GROUP_ICONS[position]
        holder.binding.tvIcon.apply {
            text = icon
            setBackgroundResource(
                if (icon == selected) R.drawable.bg_icon_tile_selected else R.drawable.bg_icon_tile
            )
            contentDescription = icon
            setOnClickListener { onSelected(icon) }
        }
    }

    class VH(val binding: ItemIconBinding) : RecyclerView.ViewHolder(binding.root)
}
