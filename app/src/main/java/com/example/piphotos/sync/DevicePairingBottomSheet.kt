package com.example.piphotos.sync

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.R
import com.example.piphotos.databinding.BottomSheetDevicePairingBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Bottom sheet dialog for discovering storage nodes on the local Wi-Fi via mDNS / subnet scan,
 * entering address manually, or scanning/pasting QR code payloads, backed by a 6-step live verification pipeline.
 */
class DevicePairingBottomSheet(
    private val onPaired: ((PairedNode) -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDevicePairingBinding? = null
    private val binding get() = _binding!!

    private var discovery: PiDeviceDiscovery? = null
    private var validator: NodePairingValidator? = null
    private var selectedTlsFingerprint: String? = null

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            handleScannedQrPayload(contents)
        }
    }

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
        validator = NodePairingValidator(context)

        // Observe Discovery UI State
        viewLifecycleOwner.lifecycleScope.launch {
            discovery?.uiState?.collectLatest { state ->
                renderDiscoveryState(state)
            }
        }

        binding.btnManualPairing.setOnClickListener {
            showManualForm()
        }

        binding.btnEnterAddressManual.setOnClickListener {
            showManualForm()
        }

        binding.btnRescan.setOnClickListener {
            discovery?.startDiscovery()
        }

        binding.btnScanQr.setOnClickListener {
            launchQrScanner()
        }

        binding.btnFormScanQr.setOnClickListener {
            launchQrScanner()
        }

        binding.btnCancelForm.setOnClickListener {
            binding.layoutManualForm.visibility = View.GONE
            binding.layoutDiscoveryContainer.visibility = View.VISIBLE
            discovery?.startDiscovery()
        }

        binding.btnVerifyAndConnect.setOnClickListener {
            startLiveVerification()
        }

        discovery?.startDiscovery()
    }

    private fun renderDiscoveryState(state: DiscoveryUiState) {
        if (binding.layoutManualForm.visibility == View.VISIBLE) {
            // User is currently filling the manual form
            return
        }

        when (state) {
            is DiscoveryUiState.Scanning -> {
                binding.tvDiscoveryStatus.text = "Scanning local Wi-Fi... (${state.secondsRemaining}s)"
                binding.progressDiscovery.visibility = View.VISIBLE
                binding.layoutDiscoveryContainer.visibility = View.VISIBLE
                binding.layoutNothingFound.visibility = View.GONE
            }
            is DiscoveryUiState.Found -> {
                binding.tvDiscoveryStatus.text = "Found ${state.nodes.size} node(s) on local network"
                binding.progressDiscovery.visibility = View.GONE
                binding.layoutDiscoveryContainer.visibility = View.VISIBLE
                binding.layoutNothingFound.visibility = View.GONE
                renderDiscoveredNodes(state.nodes)
            }
            is DiscoveryUiState.NothingFound -> {
                binding.tvDiscoveryStatus.text = "Scan completed"
                binding.progressDiscovery.visibility = View.GONE
                binding.layoutDiscoveryContainer.visibility = View.GONE
                binding.layoutNothingFound.visibility = View.VISIBLE
            }
        }
    }

    private fun renderDiscoveredNodes(nodes: List<DiscoveredNode>) {
        val container = binding.layoutNodesList
        container.removeAllViews()

        val inflater = LayoutInflater.from(requireContext())
        for (node in nodes) {
            val itemView = inflater.inflate(R.layout.item_discovered_node, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvNodeName)
            val tvEndpoint = itemView.findViewById<TextView>(R.id.tvNodeEndpoint)
            val btnConnect = itemView.findViewById<TextView>(R.id.btnConnectNode)

            tvName.text = node.name
            val fpSnippet = if (node.tlsFingerprint != null) " • ${node.tlsFingerprint.take(11)}..." else ""
            tvEndpoint.text = "${node.endpointUrl}$fpSnippet"

            val clickListener = View.OnClickListener {
                showManualForm(
                    host = node.hostAddress,
                    port = node.port,
                    tlsFingerprint = node.tlsFingerprint,
                    suggestedName = node.name
                )
            }
            itemView.setOnClickListener(clickListener)
            btnConnect.setOnClickListener(clickListener)

            container.addView(itemView)
        }
    }

    fun showManualForm(
        host: String = "",
        port: Int = 8443,
        tlsFingerprint: String? = null,
        suggestedName: String? = null
    ) {
        discovery?.stopDiscovery()
        selectedTlsFingerprint = tlsFingerprint

        binding.layoutDiscoveryContainer.visibility = View.GONE
        binding.layoutNothingFound.visibility = View.GONE
        binding.layoutManualForm.visibility = View.VISIBLE
        binding.layoutLiveSteps.visibility = View.GONE
        binding.tvValidationErrorMessage.visibility = View.GONE

        if (host.isNotEmpty()) {
            binding.etHostAddress.setText(host)
        }
        binding.etPort.setText(port.toString())
        binding.tvFormTitle.text = if (!suggestedName.isNullOrEmpty()) {
            "Pair with $suggestedName"
        } else {
            "Enter Server Address & Pairing Code"
        }
        binding.etPairingCode.requestFocus()
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan PiPhotos server pairing QR code")
            setCameraId(0)
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        try {
            qrScanLauncher.launch(options)
        } catch (e: Exception) {
            // Fallback to manual text input dialog if camera launch fails
            showQrInputDialog()
        }
    }

    private fun handleScannedQrPayload(rawText: String) {
        val parsed = NodePairingValidator.parseQrCode(rawText)
        if (parsed != null) {
            showManualForm(
                host = parsed.host,
                port = parsed.port,
                tlsFingerprint = parsed.tlsFingerprint
            )
            if (!parsed.code.isNullOrEmpty()) {
                binding.etPairingCode.setText(parsed.code)
                startLiveVerification()
            }
        } else {
            Toast.makeText(
                requireContext(),
                "Could not parse QR code. Ensure it is a valid PiPhotos code.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showQrInputDialog() {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = "Paste QR code content from server"
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(context)
            .setTitle("Scan / Paste Server QR Code")
            .setMessage("Paste the JSON or URI payload printed by the server terminal:")
            .setView(input)
            .setPositiveButton("Use") { _, _ ->
                val rawText = input.text.toString().trim()
                val parsed = NodePairingValidator.parseQrCode(rawText)
                if (parsed != null) {
                    showManualForm(
                        host = parsed.host,
                        port = parsed.port,
                        tlsFingerprint = parsed.tlsFingerprint
                    )
                    if (!parsed.code.isNullOrEmpty()) {
                        binding.etPairingCode.setText(parsed.code)
                    }
                } else {
                    Toast.makeText(context, "Could not parse QR code payload", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startLiveVerification() {
        val host = binding.etHostAddress.text.toString().trim()
        val portStr = binding.etPort.text.toString().trim()
        val code = binding.etPairingCode.text.toString().trim()

        if (host.isEmpty()) {
            binding.etHostAddress.error = "Host or IP required"
            return
        }
        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            binding.etPort.error = "Invalid port"
            return
        }
        if (code.length != 6 && code.length != 8) {
            binding.etPairingCode.error = "Enter 6-digit owner code or 8-character invite code"
            return
        }

        binding.layoutLiveSteps.visibility = View.VISIBLE
        binding.tvValidationErrorMessage.visibility = View.GONE
        binding.btnVerifyAndConnect.isEnabled = false

        // Reset step views
        resetStepView(binding.iconStep1, binding.tvStep1, "Resolve hostname")
        resetStepView(binding.iconStep2, binding.tvStep2, "TCP connect")
        resetStepView(binding.iconStep3, binding.tvStep3, "PiPhotos handshake (/api/v1/info)")
        resetStepView(binding.iconStep4, binding.tvStep4, "Verify TLS certificate fingerprint")
        resetStepView(binding.iconStep5, binding.tvStep5, "Validate pairing code")
        resetStepView(binding.iconStep6, binding.tvStep6, "Check storage capacity")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = validator?.executeValidation(
                rawHost = host,
                port = port,
                pairingCode = code,
                providedTlsFp = selectedTlsFingerprint,
                onStepUpdate = { step, state ->
                    lifecycleScope.launch {
                        updateStepUi(step, state)
                    }
                }
            )

            binding.btnVerifyAndConnect.isEnabled = true

            when (result) {
                is PairingValidationResult.Success -> {
                    val context = requireContext()
                    val node = result.pairedNode
                    SyncNodePreferences.savePairedNode(
                        context = context,
                        name = node.nodeName,
                        endpointUrl = node.endpointUrl,
                        authToken = node.authToken,
                        tlsFingerprint = node.tlsFingerprint,
                        userId = node.userId,
                        deviceId = node.deviceId,
                        serverId = node.serverId
                    )
                    MediaSyncWorker.schedulePeriodicSync(context)
                    MediaSyncWorker.enqueueImmediateSync(context)

                    onPaired?.invoke(node)
                    Toast.makeText(context, "Paired with ${node.nodeName}!", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                is PairingValidationResult.Failure -> {
                    binding.tvValidationErrorMessage.text = result.errorMessage
                    binding.tvValidationErrorMessage.visibility = View.VISIBLE
                }
                null -> {}
            }
        }
    }

    private fun updateStepUi(step: LiveCheckStep, state: LiveCheckStepState) {
        val (icon, tv) = when (step) {
            LiveCheckStep.RESOLVE_NAME -> Pair(binding.iconStep1, binding.tvStep1)
            LiveCheckStep.TCP_CONNECT -> Pair(binding.iconStep2, binding.tvStep2)
            LiveCheckStep.CHECK_SERVER_INFO -> Pair(binding.iconStep3, binding.tvStep3)
            LiveCheckStep.CHECK_CERTIFICATE -> Pair(binding.iconStep4, binding.tvStep4)
            LiveCheckStep.SUBMIT_PAIRING_CODE -> Pair(binding.iconStep5, binding.tvStep5)
            LiveCheckStep.CHECK_FREE_SPACE -> Pair(binding.iconStep6, binding.tvStep6)
        }

        val context = requireContext()
        when (state) {
            is LiveCheckStepState.Pending -> {
                resetStepView(icon, tv, step.displayName)
            }
            is LiveCheckStepState.Running -> {
                icon.setImageResource(R.drawable.ic_clock)
                icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_blue))
                tv.text = "${step.displayName} (checking...)"
                tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            is LiveCheckStepState.Passed -> {
                icon.setImageResource(R.drawable.ic_check_circle)
                icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_green))
                val detail = if (state.message.isNotEmpty()) ": ${state.message}" else ""
                tv.text = "${step.displayName}$detail"
                tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            is LiveCheckStepState.Failed -> {
                icon.setImageResource(R.drawable.ic_close)
                icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_red))
                tv.text = "${step.displayName}: Failed"
                tv.setTextColor(ContextCompat.getColor(context, R.color.status_red))
            }
        }
    }

    private fun resetStepView(icon: ImageView, tv: TextView, text: String) {
        val context = context ?: return
        icon.setImageResource(R.drawable.ic_clock)
        icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_tertiary))
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
    }

    override fun onDestroyView() {
        discovery?.stopDiscovery()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "DevicePairingBottomSheet"
    }
}
