package net.mspanc.twinsenradio.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.ArtworkMode
import net.mspanc.twinsenradio.data.BufferProfile
import net.mspanc.twinsenradio.data.ClockColors
import net.mspanc.twinsenradio.data.ClockFace
import net.mspanc.twinsenradio.data.ContentStyle
import net.mspanc.twinsenradio.data.Line
import net.mspanc.twinsenradio.data.LineContent
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.databinding.ActivitySettingsBinding
import net.mspanc.twinsenradio.playback.DiagnosticFields
import net.mspanc.twinsenradio.playback.Trace

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    /** The selected index of each list - MaterialAutoCompleteTextView holds text, not a position. */
    private val chosen = HashMap<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.toolbar.setNavigationOnClickListener { finish() }

        // --- dashboard display -------------------------------------------------
        // Labels and hints come from the Line enum, so the row descriptions
        // aren't duplicated in two places.
        b.swEnrich.isChecked = prefs.enrichWithYear
        bindLine(b.tilLineTop, b.ddLineTop, Line.TOP, prefs.lineTop)
        bindLine(b.tilLineMiddle, b.ddLineMiddle, Line.MIDDLE, prefs.lineMiddle)
        bindLine(b.tilLineBottom, b.ddLineBottom, Line.BOTTOM, prefs.lineBottom)
        // The "· album" options' labels spell out whether they'll carry a year -
        // keep that in sync with the switch without losing what's already picked.
        b.swEnrich.setOnCheckedChangeListener { _, checked ->
            listOf(b.ddLineTop, b.ddLineMiddle, b.ddLineBottom).forEach {
                bind(it, LineContent.labels(checked), pick(it))
            }
        }

        // --- artwork -------------------------------------------------------
        bind(b.ddClockFace, ClockFace.LABELS, prefs.clockFace) { updateClockOptionsEnabled(it) }
        b.swClockAlways.isChecked = prefs.clockCoverAlways
        b.swClockAlways.setOnCheckedChangeListener { _, _ ->
            updateClockOptionsEnabled(pick(b.ddClockFace))
        }
        bind(b.ddClockBg, ClockColors.BACKGROUND_LABELS, prefs.clockBackground)
        bind(b.ddClockFg, ClockColors.FOREGROUND_LABELS, prefs.clockForeground)
        bind(b.ddArtwork, ArtworkMode.LABELS, prefs.artworkMode)
        updateClockOptionsEnabled(prefs.clockFace)

        // --- diagnostics -----------------------------------------------------
        b.swDiag.isChecked = prefs.diagnosticMode
        b.swDiagApi.isChecked = prefs.diagnosticShowApiName
        b.swDiagApi.setOnCheckedChangeListener { _, _ -> renderLegend() }
        renderLegend()

        b.swTrace.isChecked = prefs.traceEnabled
        b.btnTraceClear.setOnClickListener {
            Trace.clear(this)
            // The delete runs on the trace's own thread, so a size read this
            // instant would still be the old one.
            b.tvTraceState.postDelayed({ renderTrace() }, 300)
            Toast.makeText(this, R.string.opt_trace_cleared, Toast.LENGTH_SHORT).show()
        }
        renderTrace()

        // --- the rest ----------------------------------------------------------
        bind(b.ddBrowsable, ContentStyle.LABELS, ContentStyle.valueToIndex(prefs.browsableStyle))
        bind(b.ddPlayable, ContentStyle.LABELS, ContentStyle.valueToIndex(prefs.playableStyle))
        bind(b.ddBuffer, BufferProfile.ALL.map { it.label }, prefs.bufferProfile)
        b.etM3u.setText(prefs.userM3uUrls.joinToString("\n"))
        renderHidden()

        b.btnSave.setOnClickListener { save() }
    }

    /**
     * Binds a dropdown list. Material holds text in it, but we need the
     * index - hence our own map of selected positions.
     */
    private fun bind(
        dropdown: MaterialAutoCompleteTextView,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit = {}
    ) {
        val index = selected.coerceIn(labels.indices)
        dropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        dropdown.setText(labels[index], false)
        chosen[dropdown.id] = index
        dropdown.setOnItemClickListener { _, _, position, _ ->
            chosen[dropdown.id] = position
            onPick(position)
        }
    }

    private fun bindLine(
        layout: TextInputLayout,
        dropdown: MaterialAutoCompleteTextView,
        line: Line,
        selected: Int
    ) {
        layout.hint = line.label
        layout.helperText = line.hint
        layout.isHelperTextEnabled = true
        bind(dropdown, LineContent.labels(b.swEnrich.isChecked), selected)
    }

    private fun pick(dropdown: MaterialAutoCompleteTextView): Int = chosen[dropdown.id] ?: 0

    /** Clock colors only make sense when the clock actually replaces the cover art. */
    private fun updateClockOptionsEnabled(clockFaceIndex: Int) {
        val usesClock = ClockFace.at(clockFaceIndex) != ClockFace.NONE
        listOf(b.swClockAlways, b.tilClockBg, b.tilClockFg).forEach {
            it.isEnabled = usesClock
            it.alpha = if (usesClock) 1f else 0.4f
        }
    }

    /**
     * Stations removed from the list. Built-in ones can't be deleted, so they're
     * only hidden - and this is the only place they can be recovered from.
     */
    private fun renderHidden() {
        val repo = StationRepository.get(this)
        val hidden = prefs.hidden
        if (hidden.isEmpty()) {
            b.hiddenBox.visibility = View.GONE
            return
        }
        val names = repo.allIncludingHidden()
            .filter { it.id in hidden }
            .joinToString(", ") { it.name }
        b.hiddenBox.visibility = View.VISIBLE
        b.hiddenLabel.text = getString(R.string.opt_hidden_label, names.ifBlank { "${hidden.size}" })
        b.btnRestoreHidden.setOnClickListener {
            prefs.restoreAllHidden()
            renderHidden()
            Toast.makeText(this, R.string.opt_restored_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderLegend() {
        b.tvLegend.text = DiagnosticFields.legend(b.swDiagApi.isChecked)
    }

    /** How much of a drive is on the phone, and where it sits. */
    private fun renderTrace() {
        val (count, bytes) = Trace.size(this)
        b.tvTraceState.text = if (count == 0) {
            getString(R.string.opt_trace_empty)
        } else {
            val size = if (bytes < 1024 * 1024) {
                "${bytes / 1024} kB"
            } else {
                String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
            }
            getString(R.string.opt_trace_state, count, size) + "\n" + Trace.dir(this).absolutePath
        }
    }

    private fun save() {
        prefs.lineTop = pick(b.ddLineTop)
        prefs.lineMiddle = pick(b.ddLineMiddle)
        prefs.lineBottom = pick(b.ddLineBottom)
        prefs.enrichWithYear = b.swEnrich.isChecked

        prefs.clockFace = pick(b.ddClockFace)
        prefs.clockCoverAlways = b.swClockAlways.isChecked
        prefs.clockBackground = pick(b.ddClockBg)
        prefs.clockForeground = pick(b.ddClockFg)
        prefs.artworkMode = pick(b.ddArtwork)

        prefs.diagnosticMode = b.swDiag.isChecked
        prefs.diagnosticShowApiName = b.swDiagApi.isChecked
        prefs.traceEnabled = b.swTrace.isChecked

        prefs.browsableStyle = ContentStyle.indexToValue(pick(b.ddBrowsable))
        prefs.playableStyle = ContentStyle.indexToValue(pick(b.ddPlayable))
        prefs.bufferProfile = pick(b.ddBuffer)
        prefs.userM3uUrls = b.etM3u.text?.toString().orEmpty().lines()

        lifecycleScope.launch {
            StationRepository.get(this@SettingsActivity).refreshUserLists(force = true)
            Toast.makeText(this@SettingsActivity, R.string.opt_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
