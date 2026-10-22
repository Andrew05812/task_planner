package com.taskplanner.ui.report

import android.content.Context
import android.content.Intent
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.export.ReportFormat
import com.taskplanner.data.report.export.ReportFormatting
import com.taskplanner.databinding.DialogReportRangeBinding
import com.taskplanner.databinding.DialogReportZoneBinding
import com.taskplanner.databinding.FragmentReportBinding
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * A report of one period, counted in one timezone, with the two ways it can leave the app.
 *
 * The screen asks questions and shows answers; it works nothing out. The figures come from the report repository,
 * the document from Part 4's layout, the pages from its pagination, the csv lines from its writer, the file name
 * from its naming rule and the bytes from its exporter. What belongs to this screen is the choice of period, of
 * zone and of format, the moment a hand-over starts, and the words it answers with when a file is written,
 * refused, or never asked for.
 *
 * The preview is the document rather than a description of it. The same
 * [com.taskplanner.data.report.export.PagedReport] the PDF writer is handed is drawn through the same dispatcher
 * at the width the screen has, so what the reader scrolls past is what the file will be, page for page; the csv
 * view shows the lines the writer states, with its quoting still on them. A second rendering of the same figures
 * would have been the one place in this feature where the screen and the report could disagree.
 *
 * Every control is repainted from the state and never read back as a command, which is why the chip rows carry
 * their own `renderingChips` guard: a check made while drawing the last answer must not ask the next question.
 */
@AndroidEntryPoint
class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReportViewModel by viewModels()

    /** Repainting the chips must not read back as the reader changing their mind. */
    private var renderingChips = false
    private var kindChips: List<Pair<Chip, ReportKind>> = emptyList()
    private var renderedKind: ReportKind? = null
    private var renderedFormat: ReportFormat? = null
    private var lastMessage: String? = null

    /** The opening resume is not a reason to read again: arriving already asks the question. */
    private var opened = false

    /**
     * The two pickers, one per file kind.
     *
     * A document picker takes its mime type when it is registered rather than when it is launched, so a screen
     * that offers both kinds holds two contracts and chooses between them. Each answers with the uri the reader
     * pointed at, or with nothing at all when they closed it — and the nothing goes to the ViewModel as a
     * cancellation rather than being dropped, because the difference between "changed their mind" and "could not
     * write" is the difference between a quiet screen and an error nobody made.
     */
    private val savePdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ReportFormat.PDF.mimeType)
    ) { uri -> viewModel.saveTo(uri) }

    private val saveCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ReportFormat.CSV.mimeType)
    ) { uri -> viewModel.saveTo(uri) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupKindChips()
        setupControls()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        // A report left on screen while the reader worked elsewhere is now a report read some minutes ago, and
        // what they did in the meantime belongs in it.
        if (opened) viewModel.refresh()
        opened = true
    }

    private fun setupKindChips() {
        val group = binding.reportKindChips
        kindChips = ReportKind.entries.map { kind ->
            Chip(requireContext()).apply {
                // ChipGroup.check() works with view ids, and a chip built here has none from XML.
                id = View.generateViewId()
                text = getString(kindLabel(kind))
                isCheckable = true
                isCheckedIconVisible = false
            } to kind
        }
        group.removeAllViews()
        kindChips.forEach { (chip, _) -> group.addView(chip) }
        group.setOnCheckedStateChangeListener { _, checked ->
            if (renderingChips) return@setOnCheckedStateChangeListener
            val chosen = checked.firstOrNull()?.let { id ->
                kindChips.firstOrNull { (chip, _) -> chip.id == id }?.second
            }
            chosen?.let { viewModel.chooseKind(it) }
        }
    }

    private fun setupControls() {
        binding.apply {
            reportDatesButton.setOnClickListener { askForDates() }
            reportZoneButton.setOnClickListener { askForZone() }
            reportZoomIn.setOnClickListener { viewModel.zoomIn() }
            reportZoomOut.setOnClickListener { viewModel.zoomOut() }
            reportSaveButton.setOnClickListener { askWhereToSave() }
            reportShareButton.setOnClickListener { viewModel.share() }
            reportRetryButton.setOnClickListener { viewModel.retry() }
            reportFormatChips.setOnCheckedStateChangeListener { _, checked ->
                if (renderingChips) return@setOnCheckedStateChangeListener
                val chosen = when (checked.firstOrNull()) {
                    reportFormatCsv.id -> ReportFormat.CSV
                    reportFormatPdf.id -> ReportFormat.PDF
                    else -> null
                }
                chosen?.let { viewModel.chooseFormat(it) }
            }
            // The preview has to be told how wide it is. Inside a horizontal scroller the measure spec a child
            // is handed says nothing about the window, and "fit the page to the screen" needs the screen.
            reportPagesScroll.addOnLayoutChangeListener { scroll, _, _, _, _, _, _, _, _ ->
                // The room a page can fill is the scroller's width minus the scroller's own padding: the padding
                // belongs to the screen's margins, and a sheet measured against the whole width runs underneath
                // it, which is a report whose right-hand column of figures is cut off.
                reportPages.setViewport(
                    (scroll.width - scroll.paddingStart - scroll.paddingEnd).toFloat()
                )
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderKind(state)
                    renderFormat(state)
                    renderDocument(state)
                    renderActions(state)
                    say(message(state))
                    // The message is spent whether or not it had words in it: a cancelled picker is a feedback
                    // that stays silent, and leaving it on the state would let the next frame say it twice.
                    state.feedback?.let { viewModel.feedbackShown() }
                    state.share?.let(::launch)
                }
            }
        }
    }

    // -- rendering ----------------------------------------------------------------------------------

    private fun renderKind(state: ReportState) {
        if (state.kind == renderedKind) return
        renderedKind = state.kind
        renderingChips = true
        kindChips.firstOrNull { it.second == state.kind }?.let { (chip, _) ->
            binding.reportKindChips.check(chip.id)
        }
        renderingChips = false
    }

    private fun renderFormat(state: ReportState) {
        if (state.format == renderedFormat) return
        renderedFormat = state.format
        renderingChips = true
        when (state.format) {
            ReportFormat.PDF -> binding.reportFormatChips.check(binding.reportFormatPdf.id)
            ReportFormat.CSV -> binding.reportFormatChips.check(binding.reportFormatCsv.id)
        }
        renderingChips = false
    }

    /**
     * The document, in whichever of its two shapes the format chip names.
     *
     * A read that failed and a layout that failed are two different complaints: the first has nothing to show and
     * says so, the second has the figures and cannot draw them. Neither one is left as a spinner, which is how a
     * reader ends up waiting for an answer that has already refused to arrive.
     */
    private fun renderDocument(state: ReportState) {
        val pages = state.pages

        binding.reportPages.setDocument(if (state.showingPages) pages else null)
        binding.reportPages.zoom = state.zoom
        binding.reportPagesScroll.isVisible = state.showingPages

        val rows = state.csvPreview
        binding.reportCsvScroll.isVisible = rows.isNotEmpty()
        binding.reportCsv.text = rows.joinToString("\n")

        // A missing message is a hidden row, never a zero id handed to setText.
        binding.reportEmpty.isVisible = state.emptyMessage != null
        state.emptyMessage?.let { binding.reportEmpty.setText(it) }
        binding.reportProgress.isVisible = state.isLoading && pages == null && state.csvLines.isEmpty()

        // A read that failed and a layout that failed are two complaints, and both have one place to be said.
        val problem = state.readError ?: state.previewError
        binding.reportProblem.isVisible = problem != null
        problem?.let { binding.reportProblemText.setText(it) }

        binding.reportWindowLabel.text = windowLine(state)
        binding.reportZoneLabel.text = zoneLine(state)
        binding.reportViewNote.text = noteLine(state)
    }

    private fun renderActions(state: ReportState) {
        binding.reportSaveButton.isEnabled = state.canExport
        binding.reportShareButton.isEnabled = state.canExport
        binding.reportBusy.isVisible = state.saving || state.staging
        // The zoom is put away with the sheets it acts on: rows have nothing to make bigger.
        binding.reportZoomIn.isVisible = state.showingPages
        binding.reportZoomOut.isVisible = state.showingPages
        binding.reportZoomIn.isEnabled = state.canZoomIn && state.pages != null
        binding.reportZoomOut.isEnabled = state.canZoomOut && state.pages != null
    }

    /**
     * The span the report was measured between, in the reader's own date style.
     *
     * The two days come from the window the figures were read through rather than from the period's own shape,
     * which is the difference between a header naming the span the report describes and one naming the span the
     * chip would have produced in the device's calendar while the report is being counted in another. Before the
     * first answer there are no such days, so the screen says which kind of period was asked for and nothing more.
     */
    private fun windowLine(state: ReportState): String {
        val context = requireContext()
        val window = state.report?.window
            ?: return ReportFormatting.periodWord(context, state.period)
        return getString(
            R.string.report_days_between,
            ReportFormatting.periodWord(context, state.period),
            ReportFormatting.day(context, window.firstDate),
            ReportFormatting.day(context, window.lastDate)
        )
    }

    private fun zoneLine(state: ReportState): String =
        if (state.followsDeviceZone) {
            getString(R.string.report_zone_device_note, state.zoneLabel)
        } else {
            state.zoneLabel
        }

    /**
     * What the view under this line is: how many sheets, or how many of the file's rows.
     *
     * The csv answer has two shapes because they are two different claims. A short file is on screen whole, and
     * "the first 79 of 79" would leave a reader looking for the part they were not being shown; a long one is
     * cut, and the cut has to be named before anyone mistakes a preview for the file. Nothing is said while
     * there is nothing to count — a report still being laid out does not have zero pages.
     */
    private fun noteLine(state: ReportState): String = when (state.format) {
        ReportFormat.PDF -> {
            val sheets = state.pages?.pageCount
            if (sheets == null) ""
            else resources.getQuantityString(R.plurals.report_pages_count, sheets, sheets)
        }
        ReportFormat.CSV -> when {
            state.csvRowCount == 0 -> ""
            state.csvRowsAllShown -> resources.getQuantityString(
                R.plurals.report_csv_all_rows,
                state.csvRowCount,
                state.csvRowCount
            )
            else -> getString(
                R.string.report_csv_shown_format,
                state.csvPreview.size,
                state.csvRowCount
            )
        }
    }

    // -- the two dialogs ---------------------------------------------------------------------------

    /**
     * The day the period is anchored on.
     *
     * One picker for the four kinds that hang off a single day — the day itself, the week holding it, its month,
     * its year — and two for a range, because a range is two choices and showing both is what lets a reader see
     * they have named the second one before the first. The dialog opens on the anchor the screen already holds,
     * so asking to change the days never moves the reader somewhere they did not mean to go.
     */
    private fun askForDates() {
        val state = viewModel.state.value
        if (state.kind == ReportKind.RANGE) {
            askForRange(state)
            return
        }
        val day = state.anchor
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                viewModel.chooseDate(LocalDate.of(year, month + 1, dayOfMonth))
            },
            day.year,
            day.monthValue - 1,
            day.dayOfMonth
        ).show()
    }

    /**
     * The range dialog, which applies only when the reader says so.
     *
     * The two ends are held here rather than pushed to the ViewModel as they are picked, because a half-chosen
     * range is not a question anyone wants answered twice: the reader may move the last day first and the first
     * day second, and each of those in isolation is a pair the report would refuse.
     */
    private fun askForRange(state: ReportState) {
        val context = requireContext()
        val form = DialogReportRangeBinding.inflate(layoutInflater)
        var from = state.anchor.withDayOfMonth(1)
        var to = state.anchor.withDayOfMonth(YearMonth.from(state.anchor).lengthOfMonth())
        if (state.period is ReportPeriod.Range) {
            from = state.period.from
            to = state.period.to
        }
        paintRange(form, context, from, to)

        fun pick(which: Boolean) {
            val start = if (which) from else to
            DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    val chosen = LocalDate.of(year, month + 1, dayOfMonth)
                    if (which) from = chosen else to = chosen
                    paintRange(form, context, from, to)
                },
                start.year,
                start.monthValue - 1,
                start.dayOfMonth
            ).show()
        }
        form.reportRangeFrom.setOnClickListener { pick(true) }
        form.reportRangeTo.setOnClickListener { pick(false) }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.report_range_title)
            .setView(form.root)
            .setPositiveButton(R.string.report_apply) { _, _ -> viewModel.chooseRange(from, to) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun paintRange(
        form: DialogReportRangeBinding,
        context: Context,
        from: LocalDate,
        to: LocalDate
    ) {
        form.reportRangeFrom.text = getString(
            R.string.report_range_from_format,
            ReportFormatting.day(context, from)
        )
        form.reportRangeTo.text = getString(
            R.string.report_range_to_format,
            ReportFormatting.day(context, to)
        )
    }

    /**
     * The zone the period is counted in, out of the several hundred the device knows, behind a search box.
     *
     * Each row states the offset that zone is standing at today, and that is information rather than a choice:
     * the id is what is handed to the report, so a reader who picks a place keeps that place's own
     * daylight-saving rules for the whole year instead of freezing the hour they happened to look at. The
     * device's own zone is a button of its own, because a reader who has searched their way to Tokyo has to be
     * able to come back in one press.
     */
    private fun askForZone() {
        val context = requireContext()
        val state = viewModel.state.value
        val form = DialogReportZoneBinding.inflate(layoutInflater)
        var options = emptyList<ReportZone>()
        lateinit var picker: androidx.appcompat.app.AlertDialog

        fun fill(query: String) {
            options = viewModel.zoneOptions(query)
            form.reportZoneList.adapter = ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                options.map { it.label }
            )
            form.reportZoneList.setOnItemClickListener { _, _, position, _ ->
                options.getOrNull(position)?.let {
                    viewModel.chooseZone(ZoneId.of(it.id))
                    // Choosing is the end of choosing. A picker that stays open after the row was taken asks the
                    // reader to dismiss a dialog they have already answered — and the screen behind it has
                    // already moved to the zone they picked.
                    picker.dismiss()
                }
            }
        }
        fill(state.zone.id)
        form.reportZoneDevice.isVisible = !state.followsDeviceZone
        form.reportZoneDevice.setOnClickListener {
            viewModel.useDeviceZone()
            picker.dismiss()
        }
        form.reportZoneSearch.setText(state.zone.id)
        form.reportZoneSearch.doAfterTextChanged { text -> fill(text?.toString().orEmpty()) }

        picker = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.report_zone_title)
            .setView(form.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        picker.show()
    }

    // -- the hand-overs ----------------------------------------------------------------------------

    /**
     * Asking the system where to put the file.
     *
     * The name comes from the exporter's own rule for the period being shown, so a folder of reports sorts into
     * the order the periods ran in whatever language the device speaks. The picker is opened only for a report
     * the screen has: a save of nothing would be a promise of a file that cannot exist.
     */
    private fun askWhereToSave() {
        val name = viewModel.suggestedName() ?: return
        when (viewModel.state.value.format) {
            ReportFormat.PDF -> savePdf.launch(name)
            ReportFormat.CSV -> saveCsv.launch(name)
        }
    }

    /**
     * Launching the chooser, and forgetting it in the same breath.
     *
     * The request leaves the state as soon as it has been handed over, because the state is re-delivered to every
     * frame and to every return from another app — and a sheet that opens twice is the §9 failure named "двойной
     * запуск". Nothing on the other side of the hand-over is promised: whether the receiving app saved, sent or
     * discarded the copy is not something this app can see.
     */
    private fun launch(share: Intent) {
        runCatching { startActivity(share) }.onFailure {
            Snackbar.make(binding.root, R.string.report_fail_handover, Snackbar.LENGTH_LONG).show()
        }
        viewModel.shareDelivered()
    }

    /**
     * The words the screen has to say about the last hand-over, or nothing at all.
     *
     * The state carries resource ids and the size of a file, never sentences, because a ViewModel has no
     * resources to read and a screen that showed an exception's own text would be reporting the mechanism rather
     * than the fact. A saved report is the one message with a figure in it: the bytes the exporter counted on
     * their way out, in the reader's number format, with their unit named beside them.
     */
    private fun message(state: ReportState): String? {
        val feedback = state.feedback ?: return null
        val id = feedback.messageRes ?: return null
        return if (feedback is ReportFeedback.Saved) {
            getString(
                id,
                getString(R.string.report_bytes_format, ReportFormatting.count(feedback.size))
            )
        } else {
            getString(id)
        }
    }

    /** A message once: the same sentence printed on two frames of one save is noise, not emphasis. */
    private fun say(message: String?) {
        if (message == null || message == lastMessage) return
        lastMessage = message
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun kindLabel(kind: ReportKind): Int = when (kind) {
        ReportKind.DAY -> R.string.report_period_day
        ReportKind.WEEK -> R.string.report_period_week
        ReportKind.MONTH -> R.string.report_period_month
        ReportKind.YEAR -> R.string.report_period_year
        ReportKind.RANGE -> R.string.report_period_range
    }
}
