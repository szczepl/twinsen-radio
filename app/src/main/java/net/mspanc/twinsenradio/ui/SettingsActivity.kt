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

        fill(b.spBrowsable, ContentStyle.LABELS, ContentStyle.valueToIndex(prefs.browsableStyle))
        fill(b.spPlayable, ContentStyle.LABELS, ContentStyle.valueToIndex(prefs.playableStyle))
        fill(b.spBuffer, BufferProfile.ALL.map { it.label }, prefs.bufferProfile)
        fill(b.spArtwork, ArtworkMode.LABELS, prefs.artworkMode)

        b.etM3u.setText(prefs.userM3uUrls.joinToString("\n"))
        renderLegend()

        b.swDiagApi.setOnCheckedChangeListener { _, _ -> renderLegend() }
        b.btnSave.setOnClickListener { save() }
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

    private fun renderLegend() {
        b.tvLegend.text = DiagnosticFields.legend(b.swDiagApi.isChecked)
    }

    private fun save() {
        prefs.diagnosticMode = b.swDiag.isChecked
        prefs.diagnosticShowApiName = b.swDiagApi.isChecked
        prefs.presentationMode = b.spPresentation.selectedItemPosition
        prefs.clockCoverAlways = b.swClockAlways.isChecked
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
