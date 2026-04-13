package com.todoer.manager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import com.todoer.manager.data.DisplayRow
import com.todoer.manager.data.FlatRow
import com.todoer.manager.databinding.ItemSectionBinding
import com.todoer.manager.databinding.ItemTodoGroupBinding
import com.todoer.manager.databinding.ItemTodoLineBinding

class TodoAdapter(
    private val collapsedIds: MutableSet<Int>,
    private val onToggle: (Int) -> Unit,
    private val onCollapseToggle: (Int) -> Unit,
    private val onAddSubtask: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<DisplayRow> = emptyList()

    fun submit(list: List<DisplayRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is DisplayRow.Section -> VIEW_SECTION
            is DisplayRow.TaskGroup -> VIEW_TASK_GROUP
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_SECTION -> SectionVH(ItemSectionBinding.inflate(inflater, parent, false))
            else -> TaskGroupVH(ItemTodoGroupBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = rows[position]) {
            is DisplayRow.Section -> (holder as SectionVH).bind(item.name)
            is DisplayRow.TaskGroup -> (holder as TaskGroupVH).bind(item)
        }
    }

    inner class SectionVH(private val binding: ItemSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(name: String) {
            binding.sectionTitle.text = name
        }
    }

    inner class TaskGroupVH(private val binding: ItemTodoGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: DisplayRow.TaskGroup) {
            val inflater = LayoutInflater.from(binding.root.context)
            binding.groupContent.removeAllViews()

            fun addLine(row: FlatRow) {
                val line = ItemTodoLineBinding.inflate(inflater, binding.groupContent, true)
                bindTodoLine(line, row)
            }

            addLine(group.root)

            val d = binding.root.resources.displayMetrics.density
            val divH = (1f * d).toInt().coerceAtLeast(1)
            val border = ContextCompat.getColor(binding.root.context, R.color.border)

            for (row in group.descendants) {
                val div = View(binding.root.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        divH
                    )
                    setBackgroundColor(border)
                }
                binding.groupContent.addView(div)
                addLine(row)
            }
        }
    }

    private fun bindTodoLine(binding: ItemTodoLineBinding, row: FlatRow) {
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
        row.repeatDate?.let { parts.add("Repeats $it") }
        row.dueDate?.let { parts.add("Due $it") }
        row.recurrence?.let { parts.add(it) }
        if (row.hasChildren && row.subTotal > 0) {
            parts.add("${row.subDone}/${row.subTotal} sub-tasks")
        }
        if (parts.isEmpty()) {
            binding.meta.visibility = View.GONE
        } else {
            binding.meta.visibility = View.VISIBLE
            binding.meta.text = parts.joinToString(" · ")
        }

        binding.check.setOnClickListener { onToggle(row.id) }

        if (row.hasChildren) {
            binding.collapseChevron.visibility = View.VISIBLE
            val collapsed = collapsedIds.contains(row.id)
            binding.collapseChevron.text = if (collapsed) "▸" else "▾"
            binding.collapseChevron.setOnClickListener { onCollapseToggle(row.id) }
            binding.title.setOnClickListener { onCollapseToggle(row.id) }
        } else {
            binding.collapseChevron.visibility = View.GONE
            binding.collapseChevron.setOnClickListener(null)
            binding.title.setOnClickListener(null)
        }

        binding.btnSubtask.isVisible = true
        binding.btnSubtask.setOnClickListener { onAddSubtask(row.id) }
    }

    companion object {
        private const val VIEW_SECTION = 0
        private const val VIEW_TASK_GROUP = 1
    }
}
