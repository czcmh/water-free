package com.waterfree.minwater.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.waterfree.minwater.R

class SwipeToDeleteCallback(
    private val adapter: DeviceAdapter,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        adapter.removeAt(viewHolder.adapterPosition)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (dX < 0) {
            val itemView = viewHolder.itemView
            val ctx = itemView.context
            val paint = Paint().apply {
                color = ContextCompat.getColor(ctx, R.color.status_error)
            }
            val rect = RectF(
                itemView.right.toFloat() + dX,
                itemView.top.toFloat(),
                itemView.right.toFloat(),
                itemView.bottom.toFloat(),
            )
            c.drawRoundRect(rect, 18f, 18f, paint)

            // 绘制"删除"文字
            val textPaint = Paint().apply {
                color = ContextCompat.getColor(ctx, R.color.white)
                textSize = 14f * ctx.resources.displayMetrics.scaledDensity
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val textX = rect.centerX()
            val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            c.drawText("删除", textX, textY, textPaint)
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
