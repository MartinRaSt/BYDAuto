package com.byd.charging.ui.main

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.byd.charging.R
import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import com.byd.charging.databinding.ItemSessionBinding
import com.byd.charging.util.DateUtil
import com.byd.charging.util.NumberUtil

class SessionAdapter(
    private val onEdit: (ChargingSession) -> Unit,
    private val onDelete: (ChargingSession) -> Unit
) : ListAdapter<ChargingSession, SessionAdapter.SessionViewHolder>(DiffCallback()) {

    inner class SessionViewHolder(private val binding: ItemSessionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(session: ChargingSession) {
            val ctx = binding.root.context
            val type = session.type
            val isGas = type == ChargingType.GASOLINE

            // Barevný pruh dle typu
            binding.typeStripe.setBackgroundColor(ContextCompat.getColor(ctx, type.colorRes()))

            // Typ badge
            binding.tvType.text = ctx.getString(type.labelRes())
            binding.tvType.setBackgroundColor(ContextCompat.getColor(ctx, type.colorRes()))

            // Základní data
            binding.tvDate.text = DateUtil.toDisplayDate(session.date)
            
            if (isGas) {
                binding.tvTime.visibility = View.GONE
                binding.tvPower.visibility = View.GONE
                binding.tvStartMeter.visibility = View.VISIBLE
                binding.tvStartMeter.text = ctx.getString(R.string.fmt_odometer, NumberUtil.format(session.odometer))
                binding.tvMainMeter.visibility = View.GONE
                binding.tvCharged.text = ctx.getString(R.string.fmt_refueled, NumberUtil.format(session.chargedKwh))
            } else {
                binding.tvTime.visibility = View.VISIBLE
                binding.tvPower.visibility = View.VISIBLE
                
                binding.tvTime.text = ctx.getString(R.string.fmt_time_range, session.startTime, session.endTime)
                binding.tvPower.text = ctx.getString(R.string.fmt_power, NumberUtil.format(session.powerKw))
                binding.tvCharged.text = ctx.getString(R.string.fmt_charged, NumberUtil.format(session.chargedKwh))

                // Zobrazení ODO a elektroměrů pro elektřinu
                if (type == ChargingType.GARAGE) {
                    binding.tvStartMeter.visibility = View.VISIBLE
                    binding.tvStartMeter.text = "ODO: ${NumberUtil.format(session.odometer)} km | Garáž: ${NumberUtil.format(session.garageMeterStartKwh)} kWh"
                    binding.tvMainMeter.visibility = View.VISIBLE
                    binding.tvMainMeter.text = "Hlavní: ${NumberUtil.format(session.mainMeterKwh)} kWh"
                } else {
                    binding.tvStartMeter.visibility = View.VISIBLE
                    binding.tvStartMeter.text = ctx.getString(R.string.fmt_odometer, NumberUtil.format(session.odometer))
                    binding.tvMainMeter.visibility = View.GONE
                }
            }

            // Cena (jen pokud je zadána)
            if (session.pricePerKwh > 0) {
                val cost = NumberUtil.formatCost(session.totalCost)
                binding.tvCost.text = ctx.getString(R.string.fmt_cost, cost)
                binding.tvCost.visibility = View.VISIBLE
            } else {
                binding.tvCost.visibility = View.GONE
            }

            // Místo
            if (session.locationName.isNotBlank()) {
                binding.tvLocation.visibility = View.VISIBLE
                binding.tvLocation.text = session.locationName

                if (session.hasGpsCoordinates) {
                    // Klepnutím otevřít mapu
                    binding.tvLocation.setTextColor(
                        ContextCompat.getColor(ctx, R.color.type_public)
                    )
                    binding.tvLocation.isClickable = true
                    binding.tvLocation.isFocusable = true
                    binding.tvLocation.setOnClickListener {
                        openInMaps(ctx, session)
                    }
                } else {
                    binding.tvLocation.setTextColor(
                        ContextCompat.getColor(ctx, R.color.textSecondary)
                    )
                    binding.tvLocation.isClickable = false
                    binding.tvLocation.setOnClickListener(null)
                }
            } else {
                binding.tvLocation.visibility = View.GONE
                binding.tvLocation.setOnClickListener(null)
            }

            binding.tvMainMeter.text = ctx.getString(R.string.fmt_main_meter, NumberUtil.format(session.mainMeterKwh))

            if (session.note.isNotBlank()) {
                binding.tvNote.text = session.note
                binding.tvNote.visibility = View.VISIBLE
            } else {
                binding.tvNote.visibility = View.GONE
            }

            binding.root.setOnClickListener { onEdit(session) }
            binding.btnEdit.setOnClickListener { onEdit(session) }
            binding.btnDelete.setOnClickListener { onDelete(session) }
        }

        private fun openInMaps(ctx: android.content.Context, session: ChargingSession) {
            val label = Uri.encode(session.locationName)
            val lat = session.latitude
            val lng = session.longitude
            // geo URI – funguje v Google Maps, Mapy.cz i dalších mapových aplikacích
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            try {
                ctx.startActivity(intent)
            } catch (_: Exception) {
                // Žádná mapová aplikace není nainstalována
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<ChargingSession>() {
        override fun areItemsTheSame(old: ChargingSession, new: ChargingSession) = old.id == new.id
        override fun areContentsTheSame(old: ChargingSession, new: ChargingSession) = old == new
    }
}
