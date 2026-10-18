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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.databinding.FragmentArchiveBinding
import com.taskplanner.databinding.ItemArchiveBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

private class ArchiveAdapter(
    val onRestore: (com.taskplanner.data.model.Task) -> Unit,
    val onDeleteForever: (com.taskplanner.data.model.Task) -> Unit,
    val onOpenDetail: (com.taskplanner.data.model.Task) -> Unit
) : ListAdapter<ArchiveState.ArchivedTask, ArchiveAdapter.VH>(
    object : DiffUtil.ItemCallback<ArchiveState.ArchivedTask>() {
        override fun areItemsTheSame(
            oldItem: ArchiveState.ArchivedTask,
            newItem: ArchiveState.ArchivedTask
        ) = oldItem.task.id == newItem.task.id

        override fun areContentsTheSame(
            oldItem: ArchiveState.ArchivedTask,
            newItem: ArchiveState.ArchivedTask
        ) = oldItem == newItem
    }
) {
    class VH(val binding: ItemArchiveBinding) : RecyclerView.ViewHolder(binding.root)

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemArchiveBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            archiveTitle.text = item.task.title
            val completed = item.task.archivedAt?.let { dateFormat.format(Date(it)) } ?: "—"
            val category = item.category?.name
                ?: root.context.getString(R.string.no_category)
            archiveMeta.text = root.context.getString(R.string.archive_completed_at, completed) +
                " · $category"
            restoreButton.setOnClickListener { onRestore(item.task) }
            deleteForeverButton.setOnClickListener { onDeleteForever(item.task) }
            root.setOnClickListener { onOpenDetail(item.task) }
        }
    }
}
