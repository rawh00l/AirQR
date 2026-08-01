package com.example.airqr

import android.app.Application
import android.content.ContentValues
import android.graphics.ImageFormat
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.BitSet
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverViewModel(application: Application) : AndroidViewModel(application) {

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _receivedChunksCount = MutableStateFlow(0)
    val receivedChunksCount: StateFlow<Int> = _receivedChunksCount

    private val _totalChunksCount = MutableStateFlow(0)
    val totalChunksCount: StateFlow<Int> = _totalChunksCount

    private val _fileSavedUri = MutableStateFlow<String?>(null)
    val fileSavedUri: StateFlow<String?> = _fileSavedUri

    private val qrReader = QRCodeReader()
    private val decodeHints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "ISO-8859-1"
    )

    private var currentFileId: Int? = null
    private var totalChunks: Int = 0
    private var receivedChunks = BitSet()
    private var chunkMap = mutableMapOf<Int, ByteArray>()
    
    private val isProcessing = AtomicBoolean(false)

    val imageAnalyzer = ImageAnalysis.Analyzer { imageProxy ->
        if (isProcessing.compareAndSet(false, true)) {
            processImage(imageProxy)
        } else {
            imageProxy.close()
        }
    }

    private fun processImage(image: ImageProxy) {
        try {
            if (image.format == ImageFormat.YUV_420_888 || image.format == ImageFormat.YUV_422_888 || image.format == ImageFormat.YUV_444_888) {
                val buffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                
                val source = PlanarYUVLuminanceSource(
                    data, image.width, image.height,
                    0, 0, image.width, image.height, false
                )
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val result = qrReader.decode(binaryBitmap, decodeHints)
                
                val rawBytes = result.text.toByteArray(Charsets.ISO_8859_1)
                handleDecodedBytes(rawBytes)
            }
        } catch (e: Exception) {
            // Ignore format or not found exceptions
        } finally {
            image.close()
            isProcessing.set(false)
        }
    }

    private fun handleDecodedBytes(bytes: ByteArray) {
        val frame = DataEncoderDecoder.decodeChunk(bytes) ?: return
        
        viewModelScope.launch(Dispatchers.Main) {
            if (currentFileId != frame.fileId) {
                // New file started
                currentFileId = frame.fileId
                totalChunks = frame.totalChunks.toInt()
                receivedChunks.clear()
                chunkMap.clear()
                _totalChunksCount.value = totalChunks
                _fileSavedUri.value = null
            }
            
            val index = frame.chunkIndex.toInt()
            if (!receivedChunks.get(index)) {
                receivedChunks.set(index)
                chunkMap[index] = frame.payload
                _receivedChunksCount.value = receivedChunks.cardinality()
                _progress.value = receivedChunks.cardinality().toFloat() / totalChunks.toFloat()
                
                if (receivedChunks.cardinality() == totalChunks) {
                    saveFile()
                }
            }
        }
    }

    private fun saveFile() {
        viewModelScope.launch(Dispatchers.IO) {
            val allBytes = ByteArray(chunkMap.values.sumOf { it.size })
            var offset = 0
            for (i in 0 until totalChunks) {
                val chunk = chunkMap[i] ?: return@launch
                System.arraycopy(chunk, 0, allBytes, offset, chunk.size)
                offset += chunk.size
            }

            var separatorIndex = -1
            for (i in allBytes.indices) {
                if (allBytes[i] == 0.toByte()) {
                    separatorIndex = i
                    break
                }
            }

            val fileName = if (separatorIndex != -1) {
                String(allBytes, 0, separatorIndex, Charsets.UTF_8)
            } else {
                "AirQR_Received_${System.currentTimeMillis()}.bin"
            }
            
            val fileContent = if (separatorIndex != -1) {
                allBytes.copyOfRange(separatorIndex + 1, allBytes.size)
            } else {
                allBytes
            }

            val context = getApplication<Application>().applicationContext
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirQR")
            }
            
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use {
                    it.write(fileContent)
                }
                _fileSavedUri.value = uri.toString()
            }
        }
    }
}
