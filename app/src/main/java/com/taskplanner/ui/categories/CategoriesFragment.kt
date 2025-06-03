package com.taskplanner.ui.categories

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.Category
import com.taskplanner.databinding.FragmentCategoriesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CategoriesFragment : Fragment() {
    private var _binding: FragmentCategoriesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CategoriesViewModel by viewModels()
    private lateinit var categoriesAdapter: CategoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCategoriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        categoriesAdapter = CategoryAdapter(
            onCategoryClick = { category ->
                navigateToCategoryDetail(category)
            },
            onCategoryDelete = { category ->
                showDeleteConfirmation(category)
            }
        )

        binding.recyclerViewCategories.apply {
            adapter = categoriesAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }
    }

    private fun setupFab() {
        binding.fabAddCategory.setOnClickListener {
            navigateToCategoryDetail(null)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categoriesState.collect { state ->
                    when (state) {
                        is CategoriesState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.recyclerViewCategories.visibility = View.GONE
                            binding.emptyView.visibility = View.GONE
                        }
                        is CategoriesState.Empty -> {
                            binding.progressBar.visibility = View.GONE
                            binding.recyclerViewCategories.visibility = View.GONE
                            binding.emptyView.visibility = View.VISIBLE
                        }
                        is CategoriesState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.recyclerViewCategories.visibility = View.VISIBLE
                            binding.emptyView.visibility = View.GONE
                            categoriesAdapter.submitList(state.categories)
                        }
                        is CategoriesState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.recyclerViewCategories.visibility = View.GONE
                            binding.emptyView.visibility = View.GONE
                            showError(state.message)
                        }
                    }
                }
            }
        }
    }

    private fun navigateToCategoryDetail(category: Category?) {
        try {
            val action = CategoriesFragmentDirections.actionCategoriesToCategoryDetail(category?.id ?: 0L)
            findNavController().navigate(action)
        } catch (e: Exception) {
            showError("Failed to navigate: ${e.message}")
        }
    }

    private fun showDeleteConfirmation(category: Category) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_category_title)
            .setMessage(R.string.dialog_delete_category_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteCategory(category)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_retry) {
                viewModel.loadCategories()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 