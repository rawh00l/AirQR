import jsQR from 'jsqr';
import { decodeChunk, stringToBytesIso } from './protocol.js';

let stream = null;
let isScanning = false;

let currentFileId = null;
let totalChunks = 0;
let receivedChunks = new Set();
let chunkMap = new Map();

export function initReceiver() {
  const startBtn = document.getElementById('start-camera-btn');
  const video = document.getElementById('camera-preview');
  const canvasElement = document.getElementById('camera-canvas');
  const canvas = canvasElement.getContext('2d');
  const progressText = document.getElementById('progress-text');
  const progressBar = document.getElementById('progress-bar');
  const successModal = document.getElementById('success-modal');
  const downloadBtn = document.getElementById('download-btn');
  const cameraContainer = document.getElementById('camera-container');

  startBtn.addEventListener('click', async () => {
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
      video.srcObject = stream;
      video.setAttribute('playsinline', true);
      video.play();
      startBtn.classList.add('hidden');
      cameraContainer.classList.remove('hidden');
      progressText.textContent = 'Scanning for QR stream...';
      
      requestAnimationFrame(tick);
    } catch (err) {
      console.error('Camera access denied', err);
      progressText.textContent = 'Camera access denied or unavailable.';
    }
  });

  function tick() {
    if (video.readyState === video.HAVE_ENOUGH_DATA) {
      canvasElement.height = video.videoHeight;
      canvasElement.width = video.videoWidth;
      canvas.drawImage(video, 0, 0, canvasElement.width, canvasElement.height);
      const imageData = canvas.getImageData(0, 0, canvasElement.width, canvasElement.height);
      const code = jsQR(imageData.data, imageData.width, imageData.height, {
        inversionAttempts: 'dontInvert',
      });

      if (code) {
        handleDecodedQR(code.data);
      }
    }
    
    if (stream && stream.active) {
      requestAnimationFrame(tick);
    }
  }

  function handleDecodedQR(dataStr) {
    // jsQR returns a string (often ISO-8859-1 mapped)
    const rawBytes = stringToBytesIso(dataStr);
    const frame = decodeChunk(rawBytes);
    
    if (!frame) return;
    
    if (currentFileId !== frame.fileId) {
      currentFileId = frame.fileId;
      totalChunks = frame.totalChunks;
      receivedChunks.clear();
      chunkMap.clear();
      successModal.classList.add('hidden');
    }
    
    if (!receivedChunks.has(frame.chunkIndex)) {
      receivedChunks.add(frame.chunkIndex);
      chunkMap.set(frame.chunkIndex, frame.payload);
      
      const progress = (receivedChunks.size / totalChunks) * 100;
      progressText.textContent = `Received: ${receivedChunks.size} / ${totalChunks} Chunks`;
      progressBar.style.width = `${progress}%`;
      
      if (receivedChunks.size === totalChunks) {
        assembleAndSaveFile();
      }
    }
  }

  function assembleAndSaveFile() {
    // Stop scanning
    if (stream) {
      stream.getTracks().forEach(track => track.stop());
    }
    cameraContainer.classList.add('hidden');
    progressText.textContent = 'Transfer Complete!';
    progressBar.style.width = '100%';
    
    let totalSize = 0;
    for (let i = 0; i < totalChunks; i++) {
      totalSize += chunkMap.get(i).length;
    }
    
    const allBytes = new Uint8Array(totalSize);
    let offset = 0;
    for (let i = 0; i < totalChunks; i++) {
      const chunk = chunkMap.get(i);
      allBytes.set(chunk, offset);
      offset += chunk.length;
    }
    
    // Extract filename (null byte separated)
    let separatorIndex = -1;
    for (let i = 0; i < allBytes.length; i++) {
      if (allBytes[i] === 0) {
        separatorIndex = i;
        break;
      }
    }
    
    let fileName = `AirQR_Received_${Date.now()}.bin`;
    let fileContent = allBytes;
    
    if (separatorIndex !== -1) {
      const decoder = new TextDecoder();
      fileName = decoder.decode(allBytes.slice(0, separatorIndex));
      fileContent = allBytes.slice(separatorIndex + 1);
    }
    
    const blob = new Blob([fileContent], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    
    successModal.classList.remove('hidden');
    downloadBtn.href = url;
    downloadBtn.download = fileName;
  }
}
