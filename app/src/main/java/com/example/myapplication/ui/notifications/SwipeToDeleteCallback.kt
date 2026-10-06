package com.example.myapplication.ui.notifications

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R

/**
 * Swipe a notification card left or right (both feel natural in RTL) to ask for its deletion.
 * This class only draws and reports the swipe; the delete itself is done by the ViewModel, which keeps
 * the row until the server confirms.
 */
class SwipeToDeleteCallback(
    context: Context,
    private val onSwipedItem: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val density = context.resources.displayMetrics.density
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_negative)
    }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_delete)!!
    private val iconSize = (26 * density).toInt()
    private val margin = 20 * density
    private val radius = 20 * density

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        onSwipedItem(viewHolder.bindingAdapterPosition)
    }

    override fun onChildDraw(
        c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
    ) {
        val item = vh.itemView
        if (dX != 0f) {
            // Red rounded background revealed behind the card, with a trash icon on the side it left.
            val rect = if (dX > 0) RectF(item.left.toFloat(), item.top.toFloat(), item.left + dX, item.bottom.toFloat())
            else RectF(item.right + dX, item.top.toFloat(), item.right.toFloat(), item.bottom.toFloat())
            c.drawRoundRect(rect, radius, radius, background)

            val top = item.top + (item.height - iconSize) / 2
            val left = if (dX > 0) (item.left + margin).toInt() else (item.right - margin - iconSize).toInt()
            if (rect.width() > iconSize + margin) {
                icon.setBounds(left, top, left + iconSize, top + iconSize)
                icon.draw(c)
            }
        }
        super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
    }
}
