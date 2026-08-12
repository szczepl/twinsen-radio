package net.mspanc.twinsenradio.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.databinding.ItemStationBinding

/**
 * List of stations. Used in two places - on the main screen (the button on the
 * right toggles favourites) and in the online search (the button adds the
 * station to the list) - that's why the subtitle and the action icon are parameters.
 */
class StationAdapter(
    private val subtitleFor: (Station) -> String,
    private val actionIconFor: (Station) -> Int,
    private val loadLogo: (Station, ImageView) -> Unit,
    private val onClick: (Station) -> Unit,
    private val onAction: (Station) -> Unit,
    /** Tap on the logo itself; null = the logo behaves like the rest of the row. */
    private val onLogoClick: ((Station) -> Unit)? = null
) : ListAdapter<Station, StationAdapter.VH>(DIFF) {

    inner class VH(val b: ItemStationBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val station = getItem(position)
        with(holder.b) {
            name.text = station.name
            subtitle.text = subtitleFor(station)
            loadLogo(station, logo)
            fav.setImageResource(actionIconFor(station))
            root.setOnClickListener { onClick(station) }
            if (onLogoClick != null) {
                logo.isClickable = true
                logo.setOnClickListener { onLogoClick.invoke(station) }
            }
            fav.setOnClickListener {
                onAction(station)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Station>() {
            override fun areItemsTheSame(a: Station, b: Station) = a.id == b.id
            override fun areContentsTheSame(a: Station, b: Station) = a == b
        }
    }
}
