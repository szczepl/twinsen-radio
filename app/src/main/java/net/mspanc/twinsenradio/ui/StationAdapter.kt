package net.mspanc.twinsenradio.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.databinding.ItemStationBinding

class StationAdapter(
    private val isFavourite: (Station) -> Boolean,
    private val logoResFor: (Station) -> Int,
    private val onClick: (Station) -> Unit,
    private val onToggleFavourite: (Station) -> Unit
) : ListAdapter<Station, StationAdapter.VH>(DIFF) {

    inner class VH(val b: ItemStationBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val station = getItem(position)
        with(holder.b) {
            name.text = station.name
            subtitle.text = station.genre
            logo.setImageResource(logoResFor(station))
            fav.setImageResource(
                if (isFavourite(station)) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            root.setOnClickListener { onClick(station) }
            fav.setOnClickListener {
                onToggleFavourite(station)
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
