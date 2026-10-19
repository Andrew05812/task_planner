package com.taskplanner.ui.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.backup.BackupSummary
import com.taskplanner.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@AndroidEntryPoint
class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by activityViewModels()

    private var selectedLocalProfileId: Long? = null
    private var lastShownNotice: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportTo(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handlePickedFile(it) } }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClicks()
        observeState()
    }

    private fun setupClicks() {
        binding.apply {
            exportButton.setOnClickListener {
                exportLauncher.launch(getString(R.string.export_file_name))
            }
            importButton.setOnClickListener {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            syncNowButton.setOnClickListener { viewModel.syncNow() }
            signInButton.setOnClickListener {
                val activity = requireActivity()
                if (activity is FragmentActivity) viewModel.signIn(activity)
            }
            signOutButton.setOnClickListener { confirmSignOut() }
            migrateLocalDataButton.setOnClickListener {
                selectedLocalProfileId?.let { viewModel.migrateLocalProfileData(it) }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    render(state)
                    showNotice(state.message ?: state.error)
                }
            }
        }
    }

    private fun showNotice(message: String?) {
        if (message == null || message == lastShownNotice) return
        lastShownNotice = message
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        viewModel.consumeMessages()
    }

    private fun render(state: SettingsState) {
        val googleSignedIn = state.user?.firebaseUid != null
        binding.apply {
            progressBar.isVisible = state.busy
            exportButton.isEnabled = !state.busy
            importButton.isEnabled = !state.busy
            syncNowButton.isEnabled = !state.busy && state.cloudConfigured
            signInButton.isVisible = state.cloudConfigured && !googleSignedIn
            signOutButton.isVisible = googleSignedIn
            cloudNoticeText.isVisible = !state.cloudConfigured

            accountStatusText.text = when {
                googleSignedIn -> getString(R.string.signed_in_as, state.user?.name.orEmpty())
                state.user != null -> getString(R.string.account_local_profile_format, state.user.name)
                else -> getString(R.string.google_account_missing)
            }

            syncStatusText.text = viewModel.describeSyncStatus()
            versionText.text = getString(R.string.version_format, state.versionName)

            val showMigration = googleSignedIn && state.localProfiles.isNotEmpty()
            localDataLabel.isVisible = showMigration
            localProfileLayout.isVisible = showMigration
            migrateLocalDataButton.isVisible = showMigration
            if (showMigration) bindLocalProfiles(state)
        }
    }

    private fun bindLocalProfiles(state: SettingsState) {
        val profiles = state.localProfiles
        val names = profiles.map { it.name }
        val current = selectedLocalProfileId
        if (current == null || profiles.none { it.id == current }) {
            selectedLocalProfileId = profiles.firstOrNull()?.id
        }
        binding.localProfileInput.apply {
            setAdapter(ArrayAdapter(requireContext(), R.layout.item_dropdown, names))
            profiles.firstOrNull { it.id == selectedLocalProfileId }?.let {
                if (text.toString() != it.name) setText(it.name, false)
            }
            setOnItemClickListener { _, _, position, _ ->
                selectedLocalProfileId = profiles.getOrNull(position)?.id
            }
        }
    }

    private fun handlePickedFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.previewImport(uri)
                .onSuccess { summary -> confirmImport(uri, summary) }
                .onFailure { error ->
                    Snackbar.make(
                        binding.root,
                        viewModel.importErrorText(error),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun confirmImport(uri: Uri, summary: BackupSummary) {
        val modes = arrayOf(
            getString(R.string.import_mode_merge),
            getString(R.string.import_mode_replace)
        )
        var chosen = 0
        val created = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(summary.exportedAt))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_preview_title)
            .setMessage(
                getString(
                    R.string.import_preview_summary,
                    summary.taskCount,
                    summary.categoryCount,
                    summary.groupCount,
                    summary.seriesCount,
                    summary.reminderCount,
                    summary.archivedCount,
                    created
                )
            )
            .setSingleChoiceItems(modes, chosen) { _, which -> chosen = which }
            .setPositiveButton(R.string.action_import) { _, _ ->
                if (chosen == 1) confirmReplace(uri)
                else viewModel.applyImport(uri, replaceExisting = false)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmReplace(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_replace_confirm_title)
            .setMessage(R.string.import_replace_confirm_message)
            .setPositiveButton(R.string.action_replace) { _, _ ->
                viewModel.applyImport(uri, replaceExisting = true)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sign_out)
            .setMessage(R.string.sign_out_message)
            .setPositiveButton(R.string.sign_out) { _, _ -> viewModel.signOut() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
