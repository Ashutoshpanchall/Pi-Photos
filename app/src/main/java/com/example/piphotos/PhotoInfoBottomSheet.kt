package com.example.piphotos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.piphotos.databinding.SheetPhotoInfoBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PhotoInfoBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetPhotoInfoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetPhotoInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = arguments?.getString(ARG_TITLE) ?: "--"
        val date = arguments?.getString(ARG_DATE) ?: "June 18, 2026 · 09:21 AM"

        binding.sheetTitle.text = title
        binding.sheetDate.text = date
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PhotoInfoBottomSheet"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_DATE = "arg_date"

        fun newInstance(title: String, date: String): PhotoInfoBottomSheet {
            return PhotoInfoBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_DATE, date)
                }
            }
        }
    }
}

