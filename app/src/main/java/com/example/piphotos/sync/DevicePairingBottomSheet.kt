package com.example.piphotos.sync

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.R
import com.example.piphotos.databinding.BottomSheetDevicePairingBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Bottom sheet dialog for discovering storage nodes on the local Wi-Fi via mDNS
 * or connecting directly via manual host/IP address.
 */
class DevicePairingBottomSheet(
    private val onPaired: ((PairedNode) -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDevicePairingBinding? = null
    private val binding get() = _binding!!

    private var discovery: PiDeviceDiscovery? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDevicePairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        discovery = PiDeviceDiscovery(context)

        viewLifecycleOwner.lifecycleScope.launch {
            discovery?.nodes?.collectLatest { nodes ->
                renderNodes(nodes)
            }
        }

        binding.btnManualPairing.setOnClickListener {
            showManualPairingDialog()
        }

        discovery?.startDiscovery()
    }

    private fun renderNodes(nodes: List<DiscoveredNode>) {
        val container = binding.layoutNodesList
        container.removeAllViews()

        if (nodes.isEmpty()) {
            binding.tvDiscoveryStatus.text = "Scanning local Wi-Fi via mDNS..."
            binding.progressDiscovery.visibility = View.VISIBLE
            return
        }

        binding.tvDiscoveryStatus.text = "Found ${nodes.size} node(s) on local network"
        binding.progressDiscovery.visibility = View.GONE

        val inflater = LayoutInflater.from(requireContext())
        for (node in nodes) {
            val itemView = inflater.inflate(R.layout.item_discovered_node, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvNodeName)
            val tvEndpoint = itemView.findViewById<TextView>(R.id.tvNodeEndpoint)
            val btnConnect = itemView.findViewById<TextView>(R.id.btnConnectNode)

            tvName.text = node.name
            tvEndpoint.text = node.endpointUrl

            val clickListener = View.OnClickListener {
                pairNode(node.name, node.endpointUrl)
            }
            itemView.setOnClickListener(clickListener)
            btnConnect.setOnClickListener(clickListener)

            container.addView(itemView)
        }
    }

    private fun pairNode(name: String, endpointUrl: String, token: String = "") {
        val context = requireContext()
        SyncNodePreferences.savePairedNode(context, name, endpointUrl, token)
        MediaSyncWorker.schedulePeriodicSync(context)
        MediaSyncWorker.enqueueImmediateSync(context)

        val paired = SyncNodePreferences.getPairedNode(context)
        if (paired != null) {
            onPaired?.invoke(paired)
        }

        Toast.makeText(context, "Paired with $name. Sync initiated.", Toast.LENGTH_SHORT).show()
        dismiss()
    }

    private fun showManualPairingDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val inputHost = EditText(context).apply {
            hint = "Host or IP (e.g. 192.168.1.100)"
            setSingleLine()
        }
        val inputPort = EditText(context).apply {
            hint = "Port (default: 8080)"
            setText("8080")
            setSingleLine()
        }
        val inputToken = EditText(context).apply {
            hint = "Auth Token (optional)"
            setSingleLine()
        }

        layout.addView(inputHost)
        layout.addView(inputPort)
        layout.addView(inputToken)

        AlertDialog.Builder(context)
            .setTitle("Manual Node Setup")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val host = inputHost.text.toString().trim()
                val portStr = inputPort.text.toString().trim()
                val token = inputToken.text.toString().trim()

                if (host.isEmpty()) {
                    Toast.makeText(context, "Host or IP cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val port = portStr.toIntOrNull() ?: 8080
                val endpointUrl = if (host.startsWith("http://") || host.startsWith("https://")) {
                    host
                } else {
                    "http://$host:$port"
                }

                pairNode("Home Vault", endpointUrl, token)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        discovery?.stopDiscovery()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DevicePairingBottomSheet"
    }
}

