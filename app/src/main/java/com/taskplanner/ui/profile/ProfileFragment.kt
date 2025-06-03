package com.taskplanner.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.User
import com.taskplanner.databinding.FragmentProfileBinding
import com.taskplanner.databinding.DialogProfileEditBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadCurrentUser()
    }

    private fun setupUI() {
        binding.apply {
            createProfileButton.setOnClickListener {
                showCreateProfileDialog()
            }

            editProfileButton.setOnClickListener {
                val currentState = viewModel.profileState.value
                if (currentState is ProfileState.Success) {
                    showEditProfileDialog(currentState.user)
                }
            }

            deleteProfileButton.setOnClickListener {
                val currentState = viewModel.profileState.value
                if (currentState is ProfileState.Success) {
                    showDeleteConfirmation()
                }
            }

            switchProfileButton.setOnClickListener {
                findNavController().navigate(R.id.action_profile_to_profile_selection)
            }

            switchProfileButtonNoUser.setOnClickListener {
                findNavController().navigate(R.id.action_profile_to_profile_selection)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.profileState.collect { state ->
                    when (state) {
                        is ProfileState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.contentGroup.visibility = View.GONE
                            binding.noUserGroup.visibility = View.GONE
                        }
                        is ProfileState.NoUser -> {
                            binding.progressBar.visibility = View.GONE
                            binding.contentGroup.visibility = View.GONE
                            binding.noUserGroup.visibility = View.VISIBLE
                        }
                        is ProfileState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.contentGroup.visibility = View.VISIBLE
                            binding.noUserGroup.visibility = View.GONE
                            updateUI(state.user)
                        }
                        is ProfileState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            showError(state.message)
                        }
                    }
                }
            }
        }
    }

    private fun updateUI(user: User) {
        binding.apply {
            profileName.text = user.name
            profileEmail.text = user.email
        }
    }

    private fun showCreateProfileDialog() {
        val dialogBinding = DialogProfileEditBinding.inflate(layoutInflater)
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_create_profile_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_create) { _, _ ->
                val name = dialogBinding.nameInput.text.toString()
                val email = dialogBinding.emailInput.text.toString()
                if (validateInput(name, email)) {
                    viewModel.createUser(name, email)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showEditProfileDialog(user: User) {
        val dialogBinding = DialogProfileEditBinding.inflate(layoutInflater)
        dialogBinding.nameInput.setText(user.name)
        dialogBinding.emailInput.setText(user.email)
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_edit_profile_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = dialogBinding.nameInput.text.toString()
                val email = dialogBinding.emailInput.text.toString()
                if (validateInput(name, email)) {
                    viewModel.updateUser(user.id, name, email)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_profile_title)
            .setMessage(R.string.dialog_delete_profile_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteProfile()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun validateInput(name: String, email: String): Boolean {
        if (name.isBlank()) {
            showError(getString(R.string.error_name_required))
            return false
        }
        if (email.isBlank()) {
            showError(getString(R.string.error_email_required))
            return false
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError(getString(R.string.error_invalid_email))
            return false
        }
        return true
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 