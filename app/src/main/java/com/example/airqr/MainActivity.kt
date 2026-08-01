package com.example.airqr

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.airqr.theme.AirQRTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AirQRTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AirQRApp()
                }
            }
        }
    }
}

@Composable
fun AirQRApp() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Sender", "Receiver")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> SenderScreen()
            1 -> ReceiverScreen()
        }
    }
}

@Composable
fun SenderScreen(viewModel: SenderViewModel = viewModel()) {
    val context = LocalContext.current
    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val selectedFileSize by viewModel.selectedFileSize.collectAsState()
    val qrImage by viewModel.qrImage.collectAsState()
    val isAnimating by viewModel.isAnimating.collectAsState()
    val fps by viewModel.fps.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        viewModel.handleFileSelection(context, uri)
    }

    // Adjust screen brightness
    DisposableEffect(isAnimating) {
        val window = (context as? ComponentActivity)?.window
        val originalBrightness = window?.attributes?.screenBrightness
        if (isAnimating) {
            window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        } else {
            window?.attributes = window?.attributes?.apply { screenBrightness = originalBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
        onDispose {
            window?.attributes = window?.attributes?.apply { screenBrightness = originalBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = { filePickerLauncher.launch("*/*") }) {
            Text("Select File (up to 5MB)")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedFileName != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("File: $selectedFileName")
                    Text("Size: ${selectedFileSize / 1024} KB")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (qrImage != null) {
            Image(
                bitmap = qrImage!!,
                contentDescription = "QR Code",
                modifier = Modifier.size(300.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Select a file to generate QR stream")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("FPS: $fps")
        Slider(
            value = fps.toFloat(),
            onValueChange = { viewModel.updateFps(it.toInt()) },
            valueRange = 1f..30f,
            steps = 29
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (isAnimating) viewModel.stopAnimation() else viewModel.startAnimation()
            },
            enabled = selectedFileName != null
        ) {
            Text(if (isAnimating) "Stop Scan" else "Start Scan")
        }
    }
}

@Composable
fun ReceiverScreen(viewModel: ReceiverViewModel = viewModel()) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraPreview(viewModel)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required to receive files.")
        }
    }
}

@Composable
fun CameraPreview(viewModel: ReceiverViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val progress by viewModel.progress.collectAsState()
    val receivedChunksCount by viewModel.receivedChunksCount.collectAsState()
    val totalChunksCount by viewModel.totalChunksCount.collectAsState()
    val fileSavedUri by viewModel.fileSavedUri.collectAsState()

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(Executors.newSingleThreadExecutor(), viewModel.imageAnalyzer)
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, executor)
                
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay mask
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(300.dp)
        ) {
            // Can add border or framing here
        }

        // Progress UI
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (totalChunksCount > 0) {
                Text(
                    text = "Received: $receivedChunksCount / $totalChunksCount Chunks",
                    color = Color.White
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            } else {
                Text(text = "Scanning for QR stream...", color = Color.White)
            }
        }
    }

    if (fileSavedUri != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Success") },
            text = { Text("File Received Successfully!") },
            confirmButton = {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(fileSavedUri)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(Intent.createChooser(intent, "Open File"))
                }) {
                    Text("Open File")
                }
            }
        )
    }
}
