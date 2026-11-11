package com.example.piphotos

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.piphotos.databinding.FragmentWelcomeBinding

class WelcomeFragment : Fragment() {

    interface Listener {
        fun onPermissionResult(granted: Boolean, isPartial: Boolean)
    }

    private var _binding: FragmentWelcomeBinding? = null
    private val binding get() = _binding!!

    private var listener: Listener? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkPermissionsAndNotify()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is Listener) {
            listener = context
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnGrantPermissions.setOnClickListener {
            val permissions = PermissionHelper.getRequiredPermissions()
            permissionLauncher.launch(permissions)
        }

        binding.btnOpenSettings.setOnClickListener {
            PermissionHelper.openAppSettings(requireContext())
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndNotify(isFromResume = true)
    }

    private fun checkPermissionsAndNotify(isFromResume: Boolean = false) {
        val context = context ?: return
        if (PermissionHelper.hasFullMediaAccess(context)) {
            listener?.onPermissionResult(granted = true, isPartial = false)
        } else if (PermissionHelper.hasPartialMediaAccess(context)) {
            listener?.onPermissionResult(granted = true, isPartial = true)
        } else {
            if (!isFromResume) {
                // Denied state
                binding.cardPermissionDenied.visibility = View.VISIBLE
                binding.btnOpenSettings.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "WelcomeFragment"
        fun newInstance() = WelcomeFragment()
    }
}

