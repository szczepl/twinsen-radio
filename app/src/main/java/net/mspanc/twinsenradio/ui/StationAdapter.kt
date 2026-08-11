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
 * Lista stacji. Uzywana w dwoch miejscach - na ekranie glownym (przycisk po
 * prawej przelacza ulubione) i w wyszukiwarce w sieci (przycisk dodaje stacje
 * do listy) - dlatego opis pod nazwa i ikona akcji sa parametrami.
 */
class StationAdapter(
    private val subtitleFor: (Station) -> String,
    private val actionIconFor: (Station) -> Int,
    private val loadLogo: (Station, ImageView) -> Unit,
    private val onClick: (Station) -> Unit,
    private val onAction: (Station) -> Unit,
    /** Stukniecie w samo logo; null = logo zachowuje sie jak reszta wiersza. */
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
