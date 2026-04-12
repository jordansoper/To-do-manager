package com.todoer.manager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import com.todoer.manager.data.FlatRow
import com.todoer.manager.databinding.ItemTodoBinding

class TodoAdapter(
    private val onToggle: (Int) -> Unit
) : RecyclerView.Adapter<TodoAdapter.VH>() {

    private var rows: List<FlatRow> = emptyList()

    fun submit(list: List<FlatRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTodoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position])
    }

    inner class VH(private val binding: ItemTodoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: FlatRow) {
            val depthPx = (binding.root.resources.displayMetrics.density * 16 * row.depth).toInt()
            binding.root.updatePaddingRelative(start = depthPx)

            binding.check.isChecked = row.completed
            binding.title.text = row.title
            if (row.completed) {
                binding.title.paintFlags = binding.title.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.title.paintFlags = binding.title.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            val parts = mutableListOf<String>()
            row.dueDate?.let { parts.add("Due $it") }
            row.recurrence?.let { parts.add(it) }
            if (parts.isEmpty()) {
                binding.meta.visibility = android.view.View.GONE
            } else {
                binding.meta.visibility = android.view.View.VISIBLE
                binding.meta.text = parts.joinToString(" · ")
            }

            binding.check.setOnClickListener {
                onToggle(row.id)
            }
        }
    }
}
