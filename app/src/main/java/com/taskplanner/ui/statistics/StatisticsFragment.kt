package com.taskplanner.ui.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.databinding.FragmentStatisticsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@AndroidEntryPoint
class StatisticsFragment : Fragment() {
    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatisticsViewModel by viewModels()
    private val percentFormat = NumberFormat.getPercentInstance(Locale.getDefault())
    private val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadStatistics()
    }

    private fun setupUI() {
        // Implementation of setupUI method
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.statisticsState.collectLatest { state ->
                when (state) {
                    is StatisticsState.Loading -> {
                        binding.progressBar.isVisible = true
                        binding.contentGroup.isVisible = false
                        binding.emptyView.isVisible = false
                    }
                    is StatisticsState.Empty -> {
                        binding.progressBar.isVisible = false
                        binding.contentGroup.isVisible = false
                        binding.emptyView.isVisible = true
                    }
                    is StatisticsState.Success -> {
                        binding.progressBar.isVisible = false
                        binding.contentGroup.isVisible = true
                        binding.emptyView.isVisible = false
                        updateStatistics(state)
                    }
                    is StatisticsState.Error -> {
                        showError(state.message)
                    }
                }
            }
        }
    }

    private fun updateStatistics(state: StatisticsState.Success) {
        // Update completion rate
        val completionRate = state.completionRate
        binding.completionRateText.text = percentFormat.format(completionRate)
        
        val totalTasks = state.categoryDistribution.values.sum()
        val completedTasks = (completionRate * totalTasks).toInt()
        binding.completionDetailsText.text = getString(
            R.string.completion_details,
            completedTasks,
            totalTasks
        )

        // Update category distribution
        binding.categoryContainer.removeAllViews()
        state.categoryDistribution.entries.sortedByDescending { it.value }.forEach { (category, count) ->
            val categoryView = layoutInflater.inflate(
                R.layout.item_category_stat,
                binding.categoryContainer,
                false
            ) as TextView
            
            val percentage = count.toFloat() / totalTasks
            categoryView.text = getString(
                R.string.category_stat_format,
                category,
                numberFormat.format(count),
                percentFormat.format(percentage)
            )
            
            binding.categoryContainer.addView(categoryView)
        }
    }

    private fun showError(message: String) {
        binding.progressBar.isVisible = false
        binding.contentGroup.isVisible = false
        binding.emptyView.isVisible = false
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_retry) {
                viewModel.loadStatistics()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 