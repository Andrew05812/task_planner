package com.taskplanner.ui.categories

import android.os.Bundle
import android.view.*
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.databinding.FragmentCategoryDetailBinding
import com.skydoves.colorpickerview.ColorPickerView
import com.skydoves.colorpickerview.listeners.ColorListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.core.view.MenuProvider
import androidx.core.content.ContextCompat

@AndroidEntryPoint
class CategoryDetailFragment : Fragment() {
    private var _binding: FragmentCategoryDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CategoryDetailViewModel by viewModels()
    private val args: CategoryDetailFragmentArgs by navArgs()

    private val presetColors = listOf(
        0xFFE53935.toInt(), // Red
        0xFFFB8C00.toInt(), // Orange
        0xFFFDD835.toInt(), // Yellow
        0xFF43A047.toInt(), // Green
        0xFF1E88E5.toInt(), // Blue
        0xFF8E24AA.toInt(), // Purple
        0xFFD81B60.toInt(), // Pink
        0xFF795548.toInt(), // Brown
        0xFF607D8B.toInt()  // Gray
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Удаляю addMenuProvider отсюда
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCategoryDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_category_detail, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    return when (menuItem.itemId) {
                        R.id.action_delete -> {
                            showDeleteConfirmationDialog()
                            true
                        }
                        else -> false
                    }
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
        setupUI()
        observeState()
        args.categoryId.let { categoryId ->
            if (categoryId > 0) {
                viewModel.loadCategory(categoryId)
            }
        }
    }

    private fun setupUI() {
        binding.apply {
            // Setup text change listeners
            categoryNameInput.apply {
                // Set initial text if editing existing category
                (viewModel.categoryDetailState.value as? CategoryDetailState.Success)?.name?.let { name ->
                    setText(name)
                    setSelection(name.length) // Place cursor at the end
                }
                
                doAfterTextChanged { text ->
                    viewModel.updateCategoryName(text?.toString() ?: "")
                    categoryNameLayout.error = null // Сброс ошибки при изменении
                }
                // Устанавливаем курсор в конец текста при фокусе
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        setSelection(text?.length ?: 0)
                    }
                }
            }

            // Setup preset colors
            presetColorsGroup.removeAllViews()
            presetColors.forEach { color ->
                val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(color)
                    chipStrokeWidth = 2f
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(
                        if (color == (viewModel.categoryDetailState.value as? CategoryDetailState.Success)?.color) {
                            ContextCompat.getColor(context, R.color.primary)
                        } else {
                            ContextCompat.getColor(context, android.R.color.transparent)
                        }
                    )
                    width = resources.getDimensionPixelSize(R.dimen.color_chip_size)
                    height = resources.getDimensionPixelSize(R.dimen.color_chip_size)
                    setOnClickListener {
                        viewModel.updateCategoryColor(color)
                        updateSelectedColor(color)
                        updateChipsSelection(color)
                    }
                }
                presetColorsGroup.addView(chip)
            }

            // Setup color picker
            colorPicker.setColorListener(ColorListener { color, fromUser ->
                if (fromUser) {
                    viewModel.updateCategoryColor(color)
                    updateSelectedColor(color)
                    updateChipsSelection(color)
                }
            })

            // Initialize selected color
            (viewModel.categoryDetailState.value as? CategoryDetailState.Success)?.color?.let { color ->
                updateSelectedColor(color)
                updateChipsSelection(color)
            }

            // Setup save button
            saveButton.setOnClickListener {
                if (categoryNameInput.text.isNullOrBlank()) {
                    categoryNameLayout.error = getString(R.string.error_title_required)
                } else {
                    viewModel.saveCategory()
                }
            }

            binding.buttonCancel.setOnClickListener {
                findNavController().navigateUp()
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categoryDetailState.collect { state ->
                    when (state) {
                        is CategoryDetailState.Loading -> {
                            binding.saveButton.isEnabled = false
                            binding.progressBar.visibility = View.VISIBLE
                            setFieldsEnabled(false)
                        }
                        is CategoryDetailState.Success -> {
                            binding.saveButton.isEnabled = state.isValid
                            binding.progressBar.visibility = View.GONE
                            setFieldsEnabled(true)
                            
                            // Set initial text when state becomes Success
                            if (binding.categoryNameInput.text.isNullOrEmpty()) {
                                binding.categoryNameInput.setText(state.name)
                                binding.categoryNameInput.setSelection(state.name.length)
                            }
                        }
                        is CategoryDetailState.Saved -> {
                            binding.saveButton.isEnabled = true
                            binding.progressBar.visibility = View.GONE
                            setFieldsEnabled(true)
                            showSnackbar(getString(R.string.msg_category_saved))
                            findNavController().navigateUp()
                        }
                        is CategoryDetailState.Error -> {
                            binding.saveButton.isEnabled = true
                            binding.progressBar.visibility = View.GONE
                            setFieldsEnabled(true)
                            showSnackbar(state.message)
                        }
                        is CategoryDetailState.Deleted -> {
                            findNavController().navigateUp()
                        }
                    }
                }
            }
        }
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        binding.categoryNameInput.isEnabled = enabled
        binding.colorPicker.isEnabled = enabled
    }

    private fun showDeleteConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_category_title)
            .setMessage(R.string.dialog_delete_category_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteCategory()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun updateSelectedColor(color: Int) {
        binding.selectedColorView.setBackgroundColor(color)
    }

    private fun updateChipsSelection(selectedColor: Int) {
        for (i in 0 until binding.presetColorsGroup.childCount) {
            val chip = binding.presetColorsGroup.getChildAt(i) as? com.google.android.material.chip.Chip
            chip?.chipStrokeColor = android.content.res.ColorStateList.valueOf(
                if (chip?.chipBackgroundColor?.defaultColor == selectedColor) {
                    ContextCompat.getColor(requireContext(), R.color.primary)
                } else {
                    ContextCompat.getColor(requireContext(), android.R.color.transparent)
                }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 