package com.example.airqr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SenderViewModel : ViewModel() {

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName

    private val _selectedFileSize = MutableStateFlow<Long>(0L)
    val selectedFileSize: StateFlow<Long> = _selectedFileSize

    private val _qrImage = MutableStateFlow<ImageBitmap?>(null)
    val qrImage: StateFlow<ImageBitmap?> = _qrImage

    private val _fps = MutableStateFlow<Int>(20)
    val fps: StateFlow<Int> = _fps

    private val _isAnimating = MutableStateFlow<Boolean>(false)
    val isAnimating: StateFlow<Boolean> = _isAnimating

    private var animationJob: Job? = null
    private var chunks: List<ByteArray> = emptyList()

    fun updateFps(newFps: Int) {
        _fps.value = newFps
    }

    fun handleFileSelection(context: Context, uri: Uri?) {
        if (uri == null) return
        
        viewModelScope.launch(Dispatchers.IO) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) _selectedFileName.value = it.getString(nameIndex)
                    if (sizeIndex != -1) _selectedFileSize.value = it.getLong(sizeIndex)
                }
            }

            // Read file bytes
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                // To support filenames on the other end, we'll prepend the filename and size to the first chunk? 
                // Or maybe just transfer the bytes and assume a generic name.
                // The PRD says: "Generate a 16-byte File ID (UUID) and read total size and filename. Segment payload..."
                // Since our custom header only has 12 bytes, we can embed the filename inside the payload of the chunks or just save as a generic file and ask user for extension.
                // For simplicity, let's just prepend the file name as a string separated by a null byte to the payload, then the actual bytes.
                val nameBytes = (_selectedFileName.value ?: "received_file").toByteArray(Charsets.UTF_8)
                val combinedBytes = nameBytes + byteArrayOf(0) + bytes
                prepareChunks(combinedBytes)
            }
        }
    }

    private fun prepareChunks(fileBytes: ByteArray) {
        val fileId = UUID.randomUUID().hashCode()
        val payloadSize = 300 // Max payload per chunk
        val totalChunks = Math.ceil(fileBytes.size.toDouble() / payloadSize).toInt()
        
        val newChunks = mutableListOf<ByteArray>()
        for (i in 0 until totalChunks) {
            val start = i * payloadSize
            val end = Math.min(start + payloadSize, fileBytes.size)
            val payload = fileBytes.copyOfRange(start, end)
            
            val checksum = DataEncoderDecoder.crc16(payload, payload.size)
            val frame = QRChunkFrame(
                fileId = fileId,
                totalChunks = totalChunks.toShort(),
                chunkIndex = i.toShort(),
                payloadSize = payload.size.toShort(),
                checksum = checksum,
                payload = payload
            )
            newChunks.add(DataEncoderDecoder.encodeChunk(frame))
        }
        chunks = newChunks
    }

    fun startAnimation() {
        if (chunks.isEmpty() || _isAnimating.value) return
        _isAnimating.value = true

        animationJob = viewModelScope.launch(Dispatchers.Default) {
            var currentIndex = 0
            val qrWriter = QRCodeWriter()
            val hints = mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1")
            while (_isAnimating.value) {
                val chunk = chunks[currentIndex]
                val bitmap = generateQrCode(qrWriter, chunk, hints)
                _qrImage.value = bitmap?.asImageBitmap()

                currentIndex = (currentIndex + 1) % chunks.size
                delay(1000L / _fps.value)
            }
        }
    }

    fun stopAnimation() {
        _isAnimating.value = false
        animationJob?.cancel()
        animationJob = null
    }

    private fun generateQrCode(qrWriter: QRCodeWriter, data: ByteArray, hints: Map<EncodeHintType, String>): Bitmap? {
        try {
            val content = String(data, Charsets.ISO_8859_1)
            val bitMatrix = qrWriter.encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
