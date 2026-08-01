import QRCode from 'qrcode';
import { encodeChunk, bytesToStringIso } from './protocol.js';

let chunks = [];
let isAnimating = false;
let animationTimeout = null;
let currentFps = 20;

export function initSender() {
  const fileInput = document.getElementById('file-input');
  const fileInfo = document.getElementById('file-info');
  const fileNameEl = document.getElementById('file-name');
  const fileSizeEl = document.getElementById('file-size');
  const fpsSlider = document.getElementById('fps-slider');
  const fpsValue = document.getElementById('fps-value');
  const startBtn = document.getElementById('start-scan-btn');
  const qrPlaceholder = document.getElementById('qr-placeholder');
  const qrCanvas = document.getElementById('qr-canvas');

  fpsSlider.addEventListener('input', (e) => {
    currentFps = parseInt(e.target.value);
    fpsValue.textContent = currentFps;
  });

  fileInput.addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    fileNameEl.textContent = file.name;
    fileSizeEl.textContent = (file.size / 1024).toFixed(2);
    fileInfo.classList.remove('hidden');
    startBtn.disabled = false;

    await prepareChunks(file);
    qrPlaceholder.classList.add('hidden');
    qrCanvas.style.display = 'block';
  });

  startBtn.addEventListener('click', () => {
    if (isAnimating) {
      stopAnimation();
      startBtn.textContent = 'Start Scan';
    } else {
      startAnimation(qrCanvas);
      startBtn.textContent = 'Stop Scan';
    }
  });
}

async function prepareChunks(file) {
  const arrayBuffer = await file.arrayBuffer();
  const fileBytes = new Uint8Array(arrayBuffer);
  
  const encoder = new TextEncoder();
  const nameBytes = encoder.encode(file.name);
  
  // Combine name + null byte + content
  const combinedBytes = new Uint8Array(nameBytes.length + 1 + fileBytes.length);
  combinedBytes.set(nameBytes, 0);
  combinedBytes[nameBytes.length] = 0;
  combinedBytes.set(fileBytes, nameBytes.length + 1);

  const fileId = Math.floor(Math.random() * 0xFFFFFFFF);
  const payloadSize = 300;
  const totalChunks = Math.ceil(combinedBytes.length / payloadSize);
  
  chunks = [];
  for (let i = 0; i < totalChunks; i++) {
    const start = i * payloadSize;
    const end = Math.min(start + payloadSize, combinedBytes.length);
    const payload = combinedBytes.slice(start, end);
    
    const chunkBytes = encodeChunk(fileId, totalChunks, i, payload);
    const isoString = bytesToStringIso(chunkBytes);
    chunks.push(isoString);
  }
}

function startAnimation(canvas) {
  if (chunks.length === 0 || isAnimating) return;
  isAnimating = true;
  
  let currentIndex = 0;
  
  const drawFrame = async () => {
    if (!isAnimating) return;
    
    const chunkStr = chunks[currentIndex];
    // Generate QR using binary mode via iso-8859-1 conversion (we pass the raw string and qrcode lib will use byte mode)
    await QRCode.toCanvas(canvas, [{ data: chunkStr, mode: 'byte' }], {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 300
    });
    
    currentIndex = (currentIndex + 1) % chunks.length;
    animationTimeout = setTimeout(drawFrame, 1000 / currentFps);
  };
  
  drawFrame();
}

function stopAnimation() {
  isAnimating = false;
  if (animationTimeout) {
    clearTimeout(animationTimeout);
    animationTimeout = null;
  }
}
