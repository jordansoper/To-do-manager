package com.todoer.manager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
            val d = binding.root.resources.displayMetrics.density
            val baseStart = (8 * d).toInt()
            val indent = (16 * d * row.depth).toInt()
            binding.rowInner.updatePaddingRelative(start = baseStart + indent)

            binding.check.isChecked = row.completed
            binding.title.text = row.title
            val ctx = binding.root.context
            if (row.completed) {
                binding.title.paintFlags = binding.title.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                binding.title.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            } else {
                binding.title.paintFlags = binding.title.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                binding.title.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
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
