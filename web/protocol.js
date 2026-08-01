// CRC16-CCITT table
const crc16Table = new Uint16Array(256);
const poly = 0x1021;
for (let i = 0; i < 256; i++) {
  let temp = i << 8;
  for (let j = 0; j < 8; j++) {
    if ((temp & 0x8000) !== 0) {
      temp = (temp << 1) ^ poly;
    } else {
      temp = temp << 1;
    }
  }
  crc16Table[i] = temp & 0xFFFF;
}

export function crc16(bytes) {
  let crc = 0xFFFF;
  for (let i = 0; i < bytes.length; i++) {
    crc = ((crc >>> 8) ^ crc16Table[(crc ^ bytes[i]) & 0xFF]) & 0xFFFF;
  }
  return (crc ^ 0xFFFF) & 0xFFFF;
}

export function encodeChunk(fileId, totalChunks, chunkIndex, payload) {
  const payloadSize = payload.length;
  const buffer = new ArrayBuffer(12 + payloadSize);
  const view = new DataView(buffer);
  
  view.setInt32(0, fileId);
  view.setInt16(4, totalChunks);
  view.setInt16(6, chunkIndex);
  view.setInt16(8, payloadSize);
  
  const checksum = crc16(payload);
  view.setInt16(10, checksum);
  
  const bytes = new Uint8Array(buffer);
  bytes.set(payload, 12);
  
  return bytes;
}

export function decodeChunk(bytes) {
  if (bytes.length < 12) return null;
  
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.length);
  const fileId = view.getInt32(0);
  const totalChunks = view.getInt16(4);
  const chunkIndex = view.getInt16(6);
  const payloadSize = view.getInt16(8);
  const checksum = view.getInt16(10);
  
  if (payloadSize < 0 || bytes.length - 12 < payloadSize) return null;
  
  const payload = new Uint8Array(bytes.buffer, bytes.byteOffset + 12, payloadSize);
  
  const calculatedChecksum = crc16(payload);
  if (calculatedChecksum !== checksum) {
    return null;
  }
  
  return {
    fileId,
    totalChunks,
    chunkIndex,
    payloadSize,
    checksum,
    payload
  };
}

export function bytesToStringIso(bytes) {
  let str = '';
  for (let i = 0; i < bytes.length; i++) {
    str += String.fromCharCode(bytes[i]);
  }
  return str;
}

export function stringToBytesIso(str) {
  const bytes = new Uint8Array(str.length);
  for (let i = 0; i < str.length; i++) {
    bytes[i] = str.charCodeAt(i) & 0xFF;
  }
  return bytes;
}
