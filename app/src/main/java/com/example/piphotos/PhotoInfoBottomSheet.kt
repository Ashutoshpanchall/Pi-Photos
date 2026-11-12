package com.example.piphotos

import android.media.ExifInterface
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.piphotos.databinding.SheetPhotoInfoBinding
import com.example.piphotos.media.LocalMediaIndex
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val mediaId = arguments?.getLong(ARG_MEDIA_ID, -1L) ?: -1L
        val fallbackTitle = arguments?.getString(ARG_TITLE) ?: "--"
        val fallbackDate = arguments?.getString(ARG_DATE) ?: "--"

        val item = if (mediaId != -1L) {
            LocalMediaIndex.getInstance(requireContext()).getItem(mediaId)
        } else {
            null
        }

        if (item == null) {
            binding.sheetTitle.text = fallbackTitle
            binding.sheetDate.text = fallbackDate
            binding.tvCameraDetails.text = "Camera details unavailable"
            binding.tvDimensionsDetails.text = "--"
            binding.tvStorageLocation.text = "Device Storage"
            return
        }

        // Date & Time from effective EXIF/MediaStore timestamp
        val dateFormat = SimpleDateFormat("MMMM d, yyyy · h:mm a", Locale.getDefault())
        binding.sheetDate.text = dateFormat.format(Date(item.effectiveDateMs))

        // Query ContentResolver for real filename, dimensions, size, and relative path
        var displayName = if (item.isVideo) "VID_${item.id}.mp4" else "IMG_${item.id}.jpg"
        var width = 0
        var height = 0
        var sizeBytes = item.sizeBytes
        var relativePath: String? = null

        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA
        )

        try {
            requireContext().contentResolver.query(item.contentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                    if (nameIdx != -1) cursor.getString(nameIdx)?.let { displayName = it }
                    if (widthIdx != -1) width = cursor.getInt(widthIdx)
                    if (heightIdx != -1) height = cursor.getInt(heightIdx)
                    if (sizeIdx != -1) {
                        val s = cursor.getLong(sizeIdx)
                        if (s > 0) sizeBytes = s
                    }
                    if (dataIdx != -1) relativePath = cursor.getString(dataIdx)
                }
            }
        } catch (_: Exception) {}

        binding.sheetTitle.text = displayName

        val formattedSize = formatFileSize(sizeBytes)

        if (item.isVideo) {
            val durationSec = (item.durationMs / 1000L).coerceAtLeast(0L)
            val durationStr = String.format(Locale.US, "%d:%02d", durationSec / 60, durationSec % 60)
            binding.tvCameraDetails.text = "Video · $durationStr · ${item.mimeType.substringAfter('/')}"
            binding.tvDimensionsDetails.text = if (width > 0 && height > 0) {
                "$width × $height · $formattedSize"
            } else {
                formattedSize
            }
        } else {
            // Read EXIF parameters from image stream
            var cameraMake: String? = null
            var cameraModel: String? = null
            var fNumber: String? = null
            var exposureTime: String? = null
            var iso: String? = null
            var focalLength: String? = null

            try {
                requireContext().contentResolver.openInputStream(item.contentUri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                    cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                    fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                    iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)

                    if (width <= 0) width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    if (height <= 0) height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                }
            } catch (_: Exception) {}

            val cameraName = listOfNotNull(cameraMake, cameraModel)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")
                .ifEmpty { "Camera" }

            val exposureFormatted = exposureTime?.toDoubleOrNull()?.let { sec ->
                if (sec < 1.0 && sec > 0.0) "1/${(1.0 / sec).toInt()}s" else "${sec}s"
            }

            val cameraParams = listOfNotNull(
                fNumber?.let { "f/$it" },
                exposureFormatted,
                focalLength?.toDoubleOrNull()?.let { String.format(Locale.US, "%.1fmm", it) },
                iso?.let { "ISO $it" }
            ).joinToString(" · ")

            binding.tvCameraDetails.text = if (cameraParams.isNotEmpty()) {
                "$cameraName\n$cameraParams"
            } else {
                cameraName
            }

            val mpStr = if (width > 0 && height > 0) {
                String.format(Locale.US, "%.1f MP", (width.toLong() * height.toLong()) / 1_000_000f)
            } else null

            binding.tvDimensionsDetails.text = if (width > 0 && height > 0 && mpStr != null) {
                "$width × $height · $mpStr · $formattedSize"
            } else {
                formattedSize
            }
        }

        binding.tvStorageLocation.text = relativePath ?: "Device Storage / ${item.bucketName}"
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PhotoInfoBottomSheet"
        private const val ARG_MEDIA_ID = "arg_media_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_DATE = "arg_date"

        fun newInstance(mediaId: Long): PhotoInfoBottomSheet {
            return PhotoInfoBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_MEDIA_ID, mediaId)
                }
            }
        }

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
