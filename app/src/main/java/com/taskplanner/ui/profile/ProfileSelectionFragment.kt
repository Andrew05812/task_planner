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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DividerItemDecoration
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.User
import com.taskplanner.databinding.FragmentProfileSelectionBinding
import com.taskplanner.databinding.DialogProfileEditBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import android.util.Patterns

@AndroidEntryPoint
class ProfileSelectionFragment : Fragment() {
    private var _binding: FragmentProfileSelectionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by viewModels()
    private lateinit var profileAdapter: ProfileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupUI()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        profileAdapter = ProfileAdapter(
            onProfileClick = { user ->
                viewModel.switchUser(user)
                findNavController().navigateUp()
            },
            onProfileEdit = { user ->
                showEditProfileDialog(user)
            },
            onProfileDelete = { user ->
                showDeleteConfirmation(user)
            }
        )

        binding.recyclerViewProfiles.apply {
            adapter = profileAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupUI() {
        binding.fabAddProfile.setOnClickListener {
            showCreateProfileDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.profiles.collect { profiles ->
                    profileAdapter.submitList(profiles) {
                        // Animate changes
                        if (_binding != null) {
                            binding.recyclerViewProfiles.scheduleLayoutAnimation()
                        }
                    }
                }
            }
        }
    }

    private fun showSwitchConfirmation(user: User) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_switch_profile_title)
            .setMessage(getString(R.string.dialog_switch_profile_message, user.name))
            .setPositiveButton(R.string.action_switch) { _, _ ->
                viewModel.switchUser(user)
                findNavController().navigateUp()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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

    private fun showDeleteConfirmation(user: User) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_profile_title)
            .setMessage(getString(R.string.dialog_delete_profile_message, user.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteProfile(user)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun validateInput(name: String, email: String): Boolean {
        var isValid = true

        if (name.isBlank()) {
            showError(getString(R.string.error_name_required))
            isValid = false
        }

        if (email.isNotBlank() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError(getString(R.string.error_invalid_email))
            isValid = false
        }

        return isValid
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
 