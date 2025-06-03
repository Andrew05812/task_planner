package com.taskplanner.ui.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.taskplanner.data.model.User
import com.taskplanner.databinding.ItemProfileBinding

class ProfileAdapter(
    private val onProfileClick: (User) -> Unit,
    private val onProfileEdit: (User) -> Unit,
    private val onProfileDelete: (User) -> Unit
) : ListAdapter<User, ProfileAdapter.ProfileViewHolder>(ProfileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProfileViewHolder(
        private val binding: ItemProfileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onProfileClick(getItem(position))
                }
            }

            binding.editProfileButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onProfileEdit(getItem(position))
                }
            }

            binding.deleteProfileButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onProfileDelete(getItem(position))
                }
            }
        }

        fun bind(user: User) {
            binding.apply {
                // Reset AvatarView state before binding
                avatarView.setInitials("") // Clear previous initials
                avatarView.setBackgroundColor(android.graphics.Color.TRANSPARENT) // Clear previous background

                profileName.text = user.name
                profileEmail.text = user.email
                
                // Clear any previous background artifacts
                avatarView.parent.requestLayout()

                // Ensure avatar view is clean before binding new data
                avatarView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // Set avatar
                avatarView.setInitials(if (user.avatarInitial.isNotBlank()) user.avatarInitial else "?")
                avatarView.setBackgroundColor(user.avatarColor)
                
                // Explicitly request redraw in case of update issues
                avatarView.invalidate()
                
                // Set border for profiles without email
                avatarBorder.visibility = if (user.email.isBlank()) View.VISIBLE else View.GONE
                
                // Show/hide edit and delete buttons
                editProfileButton.visibility = View.VISIBLE
                deleteProfileButton.visibility = View.VISIBLE
            }
        }
    }

    private class ProfileDiffCallback : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: User, newItem: User): Boolean {
            return oldItem == newItem
        }
    }
} 