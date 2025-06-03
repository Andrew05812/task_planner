package com.taskplanner.ui.categories

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.taskplanner.R
import com.taskplanner.data.model.Category
import com.taskplanner.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onCategoryClick: (Category) -> Unit,
    private val onCategoryDelete: (Category) -> Unit
) : ListAdapter<CategoryWithTaskCount, CategoryAdapter.CategoryViewHolder>(CategoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CategoryViewHolder(
        private val binding: ItemCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onCategoryClick(getItem(position).category)
                }
            }

            binding.root.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onCategoryDelete(getItem(position).category)
                }
                true
            }
        }

        fun bind(item: CategoryWithTaskCount) {
            binding.apply {
                categoryName.text = item.category.name
                categoryIcon.setImageResource(R.drawable.ic_category)
                categoryIcon.setColorFilter(item.category.color)
                taskCount.text = binding.root.context.resources.getQuantityString(
                    R.plurals.task_count,
                    item.taskCount,
                    item.taskCount
                )
            }
        }
    }
}

private class CategoryDiffCallback : DiffUtil.ItemCallback<CategoryWithTaskCount>() {
    override fun areItemsTheSame(oldItem: CategoryWithTaskCount, newItem: CategoryWithTaskCount): Boolean {
        return oldItem.category.id == newItem.category.id
    }

    override fun areContentsTheSame(oldItem: CategoryWithTaskCount, newItem: CategoryWithTaskCount): Boolean {
        return oldItem == newItem
    }
}

data class CategoryWithTaskCount(
    val category: Category,
    val taskCount: Int
) 