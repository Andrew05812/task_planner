package com.taskplanner.ui.categories

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
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

    /**
     * The same nine colours, named, in the same order.
     *
     * A preset chip carries no text — the colour is the whole control — so without a name a screen reader is
     * handed nine anonymous buttons and a choice it cannot make. The list is index-paired with
     * [presetColors] because that is how the chips are built and re-decorated, and a colour added to one list
     * without the other would name the wrong chip: `setupUI` requires the two sizes to match before it builds
     * anything, so the mistake is a message rather than a silent mislabel.
     */
    private val presetColorNames = listOf(
        R.string.color_name_red,
        R.string.color_name_orange,
        R.string.color_name_yellow,
        R.string.color_name_green,
        R.string.color_name_blue,
        R.string.color_name_purple,
        R.string.color_name_pink,
        R.string.color_name_brown,
        R.string.color_name_gray
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
            require(presetColors.size == presetColorNames.size) {
                "the nine preset colours and their nine names have drifted apart: ${presetColors.size} colours, " +
                    "${presetColorNames.size} names — a chip would be announced by the wrong colour's name"
            }
            presetColorsGroup.removeAllViews()
            presetColors.forEachIndexed { index, color ->
                val selected =
                    color == (viewModel.categoryDetailState.value as? CategoryDetailState.Success)?.color
                val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                    chipBackgroundColor = ColorStateList.valueOf(color)
                    // Two *dp*, not two pixels: `chipStrokeWidth` is a raw pixel value, and at this device's
                    // density 2f measured 0.76dp — a ring only someone with the colour could find.
                    chipStrokeWidth = 2f * resources.displayMetrics.density
                    chipStrokeColor = ColorStateList.valueOf(
                        if (selected) accentColour(requireContext()) else android.graphics.Color.TRANSPARENT
                    )
                    // The chip has no text of its own, so the name and the state are its only announcement.
                    contentDescription = chipLabel(index, selected)
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
                            // And paint the colour the state actually holds. `setupUI` reads the colour once,
                            // before `loadCategory` has resolved the stored one, so without this the preview
                            // drew the default black and no chip looked selected for an existing category —
                            // a display-only staleness, which is exactly why nothing noticed it: the saved
                            // value was always right. Stage 8a's tinted swatch made it visible, so the fix
                            // belongs with it.
                            updateSelectedColor(state.color)
                            updateChipsSelection(state.color)
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
                            showSnackbar(getString(state.messageRes))
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
        // Tint the oval rather than replace it. `setBackgroundColor` swapped the drawable for a colour,
        // which is why the preview lost its circle and its ring as soon as a colour was chosen.
        binding.selectedColorView.backgroundTintList = ColorStateList.valueOf(color)
    }

    /**
     * The colour the theme calls `colorPrimary`, asked of the theme rather than read from `R.color.primary`.
     *
     * `values/colors.xml` keeps `primary`, `on_surface` and `error` as shims for code that has not moved to
     * the design system yet. A rename of a shim would be a compile error, but a *re-point* — which is what a
     * design system does to its own aliases — would silently move this stroke away from every other accent in
     * the app, and no XML sweep can see a Kotlin read.
     */
    private fun accentColour(context: android.content.Context): Int {
        val value = TypedValue()
        val answered = context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, value, true
        )
        return if (answered && value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else if (answered) {
            value.data
        } else {
            ContextCompat.getColor(context, R.color.ink_accent)
        }
    }

    private fun updateChipsSelection(selectedColor: Int) {
        for (i in 0 until binding.presetColorsGroup.childCount) {
            val chip = binding.presetColorsGroup.getChildAt(i) as? com.google.android.material.chip.Chip
            val selected = chip?.chipBackgroundColor?.defaultColor == selectedColor
            chip?.chipStrokeColor = ColorStateList.valueOf(
                if (selected) accentColour(requireContext()) else android.graphics.Color.TRANSPARENT
            )
            // The stroke is the sighted reader's signal and the description is everyone else's; both are
            // rewritten here so the two can never disagree after a tap.
            if (i < presetColorNames.size) chip?.contentDescription = chipLabel(i, selected)
        }
    }

    /**
     * What a preset chip is called, and whether it is the chosen one.
     *
     * The name comes from the index-paired [presetColorNames], so a colour that is added to [presetColors]
     * without a name is refused here rather than announced as its neighbour.
     */
    private fun chipLabel(index: Int, selected: Boolean): String {
        val name = getString(presetColorNames[index])
        return if (selected) "$name, ${getString(R.string.state_selected)}" else name
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 