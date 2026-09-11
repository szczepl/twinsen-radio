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
import net.mspanc.twinsenradio.data.ArtContent
import net.mspanc.twinsenradio.data.ArtworkMode
import net.mspanc.twinsenradio.data.BufferProfile
import net.mspanc.twinsenradio.data.ClockColors
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

    /** Which LineContent values each line dropdown offers - see [bindLine]. */
    private val lineChoices = HashMap<Int, List<LineContent>>()

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
        bindLine(b.tilLineTop, b.ddLineTop, Line.TOP, LineContent.WHEN_PLAYING, prefs.lineTop)
        bindLine(b.tilLineMiddle, b.ddLineMiddle, Line.MIDDLE, LineContent.WHEN_PLAYING, prefs.lineMiddle)
        bindLine(b.tilLineBottom, b.ddLineBottom, Line.BOTTOM, LineContent.WHEN_PLAYING, prefs.lineBottom)
        bindLine(b.tilLineTopIdle, b.ddLineTopIdle, Line.TOP, LineContent.WHEN_IDLE, prefs.lineTopIdle)
        bindLine(b.tilLineMiddleIdle, b.ddLineMiddleIdle, Line.MIDDLE, LineContent.WHEN_IDLE, prefs.lineMiddleIdle)
        bindLine(b.tilLineBottomIdle, b.ddLineBottomIdle, Line.BOTTOM, LineContent.WHEN_IDLE, prefs.lineBottomIdle)
        // The "· album" options' labels spell out whether they'll carry a year -
        // keep that in sync with the switch without losing what's already picked.
        // Only the playing set has those entries; the other list is unaffected.
        b.swEnrich.setOnCheckedChangeListener { _, checked ->
            listOf(b.ddLineTop, b.ddLineMiddle, b.ddLineBottom).forEach {
                bind(it, LineContent.labels(LineContent.WHEN_PLAYING, checked), pick(it))
            }
        }

        // --- artwork -------------------------------------------------------
        // One choice per state, exactly like the lines above it.
        bind(b.ddArtPlaying, ArtContent.LABELS, prefs.artPlaying) { updateClockOptionsEnabled() }
        bind(b.ddArtIdle, ArtContent.LABELS, prefs.artIdle) { updateClockOptionsEnabled() }
        bind(b.ddClockBg, ClockColors.BACKGROUND_LABELS, prefs.clockBackground)
        bind(b.ddClockFg, ClockColors.FOREGROUND_LABELS, prefs.clockForeground)
        bind(b.ddArtwork, ArtworkMode.LABELS, prefs.artworkMode)
        updateClockOptionsEnabled()

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

    /**
     * @param choices which contents this line may hold - the playing set offers
     *   the track, the between-tracks set does not.
     * @param selected the stored value, which is a [LineContent] ordinal and
     *   **not** a position in [choices]. The two stopped being the same thing
     *   once each list became a different subset, so both directions go through
     *   [lineChoices].
     */
    private fun bindLine(
        layout: TextInputLayout,
        dropdown: MaterialAutoCompleteTextView,
        line: Line,
        choices: List<LineContent>,
        selected: Int
    ) {
        layout.hint = line.label
        layout.helperText = line.hint
        layout.isHelperTextEnabled = true
        lineChoices[dropdown.id] = choices
        // A value this list doesn't offer - an old setting, or one saved by a
        // later version - falls back to the first entry rather than to whatever
        // happens to sit at that index.
        val position = choices.indexOf(LineContent.at(selected)).coerceAtLeast(0)
        bind(dropdown, LineContent.labels(choices, b.swEnrich.isChecked), position)
    }

    /** Turns a dropdown's position back into the ordinal that gets stored. */
    private fun pickLine(dropdown: MaterialAutoCompleteTextView): Int {
        val choices = lineChoices[dropdown.id] ?: LineContent.WHEN_PLAYING
        return choices.getOrElse(pick(dropdown)) { choices.first() }.ordinal
    }

    private fun pick(dropdown: MaterialAutoCompleteTextView): Int = chosen[dropdown.id] ?: 0

    /** Clock colours matter only if at least one of the two states draws a clock. */
    private fun updateClockOptionsEnabled() {
        val usesClock = listOf(b.ddArtPlaying, b.ddArtIdle)
            .any { ArtContent.at(pick(it)).clockFace != null }
        listOf(b.tilClockBg, b.tilClockFg).forEach {
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
        prefs.lineTop = pickLine(b.ddLineTop)
        prefs.lineMiddle = pickLine(b.ddLineMiddle)
        prefs.lineBottom = pickLine(b.ddLineBottom)
        prefs.lineTopIdle = pickLine(b.ddLineTopIdle)
        prefs.lineMiddleIdle = pickLine(b.ddLineMiddleIdle)
        prefs.lineBottomIdle = pickLine(b.ddLineBottomIdle)
        prefs.enrichWithYear = b.swEnrich.isChecked

        prefs.artPlaying = pick(b.ddArtPlaying)
        prefs.artIdle = pick(b.ddArtIdle)
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
