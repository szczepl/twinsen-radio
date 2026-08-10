package net.mspanc.twinsenradio.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.ArtworkMode
import net.mspanc.twinsenradio.data.BufferProfile
import net.mspanc.twinsenradio.data.ClockColors
import net.mspanc.twinsenradio.data.ClockFace
import net.mspanc.twinsenradio.data.ContentStyle
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Presentation
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.databinding.ActivitySettingsBinding
import net.mspanc.twinsenradio.playback.DiagnosticFields

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        b.swDiag.isChecked = prefs.diagnosticMode
        b.swDiagApi.isChecked = prefs.diagnosticShowApiName

        fill(b.spPresentation, Presentation.LABELS, prefs.presentationMode)
        b.swClockAlways.isChecked = prefs.clockCoverAlways
        fill(b.spClockBg, ClockColors.BACKGROUND_LABELS, prefs.clockBackground)
        fill(b.spClockFg, ClockColors.FOREGROUND_LABELS, prefs.clockForeground)
        b.swEnrich.isChecked = prefs.enrichWithAlbum

        fill(b.spBrowsable, ContentStyle.LABELS, ContentStyle.valueToIndex(prefs.browsableStyle))
        fill(b.spPlayable, ContentStyle.LABELS, ContentStyle.valueToIndex(prefs.playableStyle))
        fill(b.spBuffer, BufferProfile.ALL.map { it.label }, prefs.bufferProfile)
        fill(b.spArtwork, ArtworkMode.LABELS, prefs.artworkMode)

        b.etM3u.setText(prefs.userM3uUrls.joinToString("\n"))
        renderLegend()

        b.swDiagApi.setOnCheckedChangeListener { _, _ -> renderLegend() }
        b.btnSave.setOnClickListener { save() }

        // Ustawienia zegara-okladki maja sens tylko przy ukladach, ktore go uzywaja
        b.spPresentation.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) = updateClockOptionsEnabled(position)

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        updateClockOptionsEnabled(prefs.presentationMode)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun fill(spinner: Spinner, labels: List<String>, selected: Int) {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        spinner.setSelection(selected.coerceIn(labels.indices))
    }

    private fun updateClockOptionsEnabled(presentationIndex: Int) {
        val usesClockCover = Presentation.at(presentationIndex).clockFace != ClockFace.NONE
        listOf(b.swClockAlways, b.spClockBg, b.spClockFg).forEach {
            it.isEnabled = usesClockCover
            it.alpha = if (usesClockCover) 1f else 0.4f
        }
    }

    private fun renderLegend() {
        b.tvLegend.text = DiagnosticFields.legend(b.swDiagApi.isChecked)
    }

    private fun save() {
        prefs.diagnosticMode = b.swDiag.isChecked
        prefs.diagnosticShowApiName = b.swDiagApi.isChecked
        prefs.presentationMode = b.spPresentation.selectedItemPosition
        prefs.clockCoverAlways = b.swClockAlways.isChecked
        prefs.clockBackground = b.spClockBg.selectedItemPosition
        prefs.clockForeground = b.spClockFg.selectedItemPosition
        prefs.enrichWithAlbum = b.swEnrich.isChecked
        prefs.browsableStyle = ContentStyle.indexToValue(b.spBrowsable.selectedItemPosition)
        prefs.playableStyle = ContentStyle.indexToValue(b.spPlayable.selectedItemPosition)
        prefs.bufferProfile = b.spBuffer.selectedItemPosition
        prefs.artworkMode = b.spArtwork.selectedItemPosition
        prefs.userM3uUrls = b.etM3u.text?.toString().orEmpty().lines()

        lifecycleScope.launch {
            StationRepository.get(this@SettingsActivity).refreshUserLists(force = true)
            Toast.makeText(this@SettingsActivity, R.string.opt_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
