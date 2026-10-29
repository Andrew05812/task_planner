package com.taskplanner.ui.archive

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
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.databinding.FragmentArchiveBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ArchiveFragment : Fragment() {

    private var _binding: FragmentArchiveBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ArchiveViewModel by viewModels()
    private lateinit var adapter: ArchiveAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArchiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ArchiveAdapter(
            onRestore = { task -> viewModel.restore(task) },
            onDeleteForever = { task ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_delete_task_title)
                    .setMessage(R.string.dialog_delete_task_message)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        viewModel.deleteForever(task)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
            onOpenDetail = { task ->
                findNavController().navigate(
                    ArchiveFragmentDirections.actionArchiveToTaskDetail(task.id)
                )
            }
        )
        binding.archiveList.layoutManager = LinearLayoutManager(requireContext())
        binding.archiveList.adapter = adapter

        binding.archiveSearch.doAfterTextChanged {
            viewModel.setSearch(it?.toString().orEmpty())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    adapter.submitList(state.items)
                    binding.archiveProgress.visibility =
                        if (state.isLoading) View.VISIBLE else View.GONE
                    binding.archiveEmpty.visibility =
                        if (!state.isLoading && state.items.isEmpty()) View.VISIBLE else View.GONE
                    state.error?.let {
                        Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
