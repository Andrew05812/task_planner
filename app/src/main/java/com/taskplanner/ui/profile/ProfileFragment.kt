package com.taskplanner.ui.profile

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
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
import com.taskplanner.databinding.RowListBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The account screen: who is signed in, and the eight things that can be done about it.
 *
 * Stage 5 changed what a row is and nothing about what a row does. The eight `MaterialButton`s became eight
 * `row_list` includes under four headers, so every listener now hangs on `binding.<name>.root` — the whole
 * 56dp line is the target, which is what a finger aims at and a screen reader announces. The dialogs, the
 * validation and every `viewModel.*` call are the code that was here before, unchanged, including the
 * deletion path: this fragment's business logic is not a design surface.
 */
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
        bindRows()

        binding.apply {
            createProfileButton.setOnClickListener {
                showCreateProfileDialog()
            }

            editProfileButton.root.setOnClickListener {
                val currentState = viewModel.profileState.value
                if (currentState is ProfileState.Success) {
                    showEditProfileDialog(currentState.user)
                }
            }

            deleteProfileButton.root.setOnClickListener {
                val currentState = viewModel.profileState.value
                if (currentState is ProfileState.Success) {
                    showDeleteConfirmation(currentState.user)
                }
            }

            switchProfileButton.root.setOnClickListener {
                findNavController().navigate(R.id.action_profile_to_profile_selection)
            }

            openSettingsButton.root.setOnClickListener {
                findNavController().navigate(R.id.action_profile_to_settings)
            }

            openGoalsButton.root.setOnClickListener {
                findNavController().navigate(R.id.action_profile_to_goals)
            }

            openHabitsButton.root.setOnClickListener {
                findNavController().navigate(R.id.action_profile_to_habits)
            }

            openInsightsButton.root.setOnClickListener {
                findNavController().navigate(R.id.action_profile_to_insights)
            }

            openReportsButton.root.setOnClickListener {
                findNavController().navigate(R.id.action_profile_to_reports)
            }

            switchProfileButtonNoUser.setOnClickListener {
                findNavController().navigate(R.id.action_profile_to_profile_selection)
            }
        }
    }

    /**
     * What each of the eight rows is, in the order the screen lists them.
     *
     * The pairs are the same words the buttons carried, so the copy is not new; what is new is that every
     * row has a glyph and the row says it in the same words a reader would use out loud.
     */
    private fun bindRows() {
        binding.run {
            editProfileButton.row(R.string.action_edit_profile, R.drawable.ic_edit)
            switchProfileButton.row(R.string.action_switch_profile, R.drawable.ic_profile)
            openGoalsButton.row(R.string.title_goals, R.drawable.ic_goal)
            openHabitsButton.row(R.string.title_habits, R.drawable.ic_habit)
            openInsightsButton.row(R.string.title_insights, R.drawable.ic_insight)
            openReportsButton.row(R.string.title_reports, R.drawable.ic_report)
            openSettingsButton.row(R.string.title_settings, R.drawable.ic_settings)
            deleteProfileButton.row(R.string.action_delete_profile, R.drawable.ic_delete, danger = true)
        }
    }

    private fun RowListBinding.row(@StringRes titleRes: Int, @DrawableRes iconRes: Int, danger: Boolean = false) {
        val words = getString(titleRes)
        rowTitle.text = words
        rowIcon.setImageResource(iconRes)
        if (danger) {
            val error = ContextCompat.getColor(requireContext(), R.color.ink_error)
            rowIcon.imageTintList = ColorStateList.valueOf(error)
        }
        // The row is one target, so it is announced as one thing. The icon inside it is decoration — a
        // stopwatch next to "Goals" does not need to be read out as well as the word.
        root.contentDescription = words
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
            profileAvatar.setInitials(user.name)
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

    /**
     * The one confirmation that has to say what it costs.
     *
     * The message is formatted here rather than passed as a resource id, because `setMessage(int)` does not
     * substitute the placeholder and the reader was being shown "%1$s". The name travels in, and the words
     * name what goes with it.
     */
    private fun showDeleteConfirmation(user: User) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_profile_title)
            .setMessage(getString(R.string.dialog_delete_profile_message, user.name))
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
