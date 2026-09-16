package com.waterfree.minwater.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.waterfree.minwater.R
import com.waterfree.minwater.data.DeviceItem
import com.waterfree.minwater.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val items: MutableList<DeviceItem>,
    private val onRefresh: (DeviceItem) -> Unit,
    private val onOpen: (DeviceItem) -> Unit,
    private val onDelete: (DeviceItem, Int) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun replaceAll(newItems: List<DeviceItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            val item = items[position]
            items.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, items.size)
            onDelete(item, position)
        }
    }

    fun getItem(position: Int): DeviceItem? = items.getOrNull(position)

    inner class DeviceViewHolder(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {

        private val unitFloorRegex = Regex("""(\d+)单元(\d+)楼""")

        fun bind(item: DeviceItem) {
            val ctx = binding.root.context

            // 状态标签
            val statusText = item.statusText.ifBlank { "未知" }
            binding.deviceStatus.text = statusText
            val (textColor, bgRes) = when (statusText) {
                "空闲" -> R.color.status_online to R.drawable.status_badge_online
                "当前用户" -> R.color.status_mine to R.drawable.status_badge_mine
                "使用中" -> R.color.status_inuse to R.drawable.status_badge_inuse
                "离线" -> R.color.status_offline to R.drawable.status_badge_bg
                else -> R.color.text_secondary to R.drawable.status_badge_bg
            }
            binding.deviceStatus.setTextColor(ContextCompat.getColor(ctx, textColor))
            binding.deviceStatus.setBackgroundResource(bgRes)
            binding.deviceStatus.setOnClickListener { onRefresh(item) }

            // 位置解析：楼+单元命中 → 大 badge；否则 → 降级为粗体标题
            val position = item.position
            val match = unitFloorRegex.find(position)
            val metaPrefix: String
            if (match != null) {
                val unit = match.groupValues[1]
                val floor = match.groupValues[2]
                binding.floorUnitContainer.visibility = View.VISIBLE
                binding.fallbackTitle.visibility = View.GONE
                binding.titleSpacer.visibility = View.VISIBLE
                binding.floorNumber.text = floor
                binding.unitNumber.text = unit
                metaPrefix = position.substring(0, match.range.first).trim()
            } else {
                binding.floorUnitContainer.visibility = View.GONE
                binding.fallbackTitle.visibility = View.VISIBLE
                binding.titleSpacer.visibility = View.GONE
                binding.fallbackTitle.text =
                    position.ifBlank { ctx.getString(R.string.position_unknown) }
                metaPrefix = ""
            }
            binding.fallbackTitle.setOnClickListener { onRefresh(item) }

            // 副标题：位置前缀 · #设备编号
            val metaParts = buildList {
                if (metaPrefix.isNotBlank()) add(metaPrefix)
                if (item.code.isNotBlank()) add("#${item.code}")
            }
            binding.deviceMeta.text = metaParts.joinToString("  ·  ")
            binding.deviceMeta.visibility = if (metaParts.isEmpty()) View.GONE else View.VISIBLE
            binding.deviceMeta.setOnClickListener { onRefresh(item) }

            val buttonText = when {
                item.isOpening -> ctx.getString(R.string.opening_water)
                statusText == "当前用户" -> ctx.getString(R.string.current_user_using)
                statusText == "使用中" -> ctx.getString(R.string.device_busy)
                statusText == "离线" -> ctx.getString(R.string.device_offline)
                else -> ctx.getString(R.string.one_tap_water)
            }
            binding.openButton.text = buttonText
            binding.openButton.isEnabled = !item.isOpening && statusText == "空闲"
            binding.openButton.alpha = if (binding.openButton.isEnabled) 1f else 0.68f
            binding.openButton.setOnClickListener { onOpen(item) }
        }
    }
}
