package com.example.wifibluetoothrecorder

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal class SignalGroupAdapter(private val groups: List<SignalGroup>, private val vendors: MacVendors?,
    private val onClick: (SignalGroup, String, String) -> Unit) : RecyclerView.Adapter<SignalGroupAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    override fun getItemCount() = groups.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_signal_group,parent,false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val group = groups[position]
        val view = holder.itemView
        val context = view.context
        val name = name(context, group)
        val detail = when(group.type) {
            "wifi" -> group.details.joinToString(" / ").ifBlank { context.getString(R.string.unknown_security) }
            "bluetooth" -> vendors?.lookup(group.address) ?: context.getString(
                if(MacVendors.isLocal(group.address)) R.string.private_address else R.string.unknown_vendor)
            else -> if(group.details.isEmpty()) context.getString(R.string.gps_fix)
                else context.getString(R.string.gps_accuracy,group.details.joinToString(" / "))
        }
        val count = context.getString(R.string.group_observations,UiFormat.number(group.count),DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(group.lastSeen)))
        view.findViewById<TextView>(R.id.groupName).text = name
        view.findViewById<TextView>(R.id.groupDetail).text = detail
        view.findViewById<TextView>(R.id.groupAddress).apply {
            text = group.address
            visibility = if(group.address.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.groupCount).text = count
        view.findViewById<ImageView>(R.id.groupIcon).setImageResource(when(group.type) {
            "wifi" -> R.drawable.ic_wifi
            "bluetooth" -> R.drawable.ic_bluetooth
            else -> R.drawable.ic_pin
        })
        view.contentDescription = context.getString(R.string.group_map_accessibility,name,detail,group.address,count)
        view.setOnClickListener { onClick(group,name,detail) }
    }
    companion object {
        fun name(context: Context, group: SignalGroup): String = if(group.type == "location")
            String.format(Locale.US,"%.6f, %.6f",group.firstPoint.latitude,group.firstPoint.longitude)
        else group.name.ifBlank { context.getString(if(group.type == "wifi") R.string.hidden_network else R.string.unnamed_device) }
    }
}

internal object VendorDirectory {
    @Volatile private var cached: MacVendors? = null
    fun get(context: Context): MacVendors = cached ?: synchronized(this) {
        cached ?: MacVendors.read(context.assets.open("mac-vendors.tsv").reader(Charsets.UTF_8)).also { cached=it }
    }
}
