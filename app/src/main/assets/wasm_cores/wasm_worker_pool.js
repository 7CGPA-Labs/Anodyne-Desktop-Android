/**
 * Anodyne Desktop - Shared WASM Worker Pool Coordinator
 * Orchestrates off-screen execution of storage, document, media, remote work, and AI tasks.
 */

// Import Core wrappers dynamically for modular processing
try {
    importScripts('duckdb_core.js');
    importScripts('tesseract_core.js');
    importScripts('ffmpeg_core.js');
    importScripts('guacamole_core.js');
    importScripts('webtorrent_core.js');
    importScripts('transformers_core.js');
    importScripts('sqlite_core.js');
    importScripts('pdfium_core.js');
    importScripts('libarchive_core.js');
    importScripts('imagemagick_core.js');
} catch (e) {
    console.warn("Failed to dynamically importScript cores. Operating in standalone fallback mode.", e);
}

const workers = {};

// Instantiations of the Core classes
const duckDbInstance = typeof DuckDbCore !== 'undefined' ? new DuckDbCore() : null;
const tesseractInstance = typeof TesseractCore !== 'undefined' ? new TesseractCore() : null;
const ffmpegInstance = typeof FFMpegCore !== 'undefined' ? new FFMpegCore() : null;
const guacamoleInstance = typeof GuacamoleCore !== 'undefined' ? new GuacamoleCore() : null;
const webTorrentInstance = typeof WebTorrentCore !== 'undefined' ? new WebTorrentCore() : null;
const transformersInstance = typeof TransformersCore !== 'undefined' ? new TransformersCore() : null;

// WASM C++ Core Module instances
let sqliteModule = null;
let sqliteInstance = null;
let pdfiumModule = null;
let pdfiumInstance = null;
let libarchiveModule = null;
let libarchiveInstance = null;
let imagemagickModule = null;
let imagemagickInstance = null;

if (typeof createSqliteCoreModule !== 'undefined') {
    createSqliteCoreModule().then(m => { sqliteModule = m; sqliteInstance = new m.SQLiteCore(); }).catch(e => console.error("WASM SQLite failed: ", e));
}
if (typeof createPdfiumCoreModule !== 'undefined') {
    createPdfiumCoreModule().then(m => { pdfiumModule = m; pdfiumInstance = new m.PDFiumCore(); }).catch(e => console.error("WASM PDFium failed: ", e));
}
if (typeof createLibarchiveCoreModule !== 'undefined') {
    createLibarchiveCoreModule().then(m => { libarchiveModule = m; libarchiveInstance = new m.LibarchiveCore(); }).catch(e => console.error("WASM Libarchive failed: ", e));
}
if (typeof createImageMagickCoreModule !== 'undefined') {
    createImageMagickCoreModule().then(m => { imagemagickModule = m; imagemagickInstance = new m.ImageMagickCore(); }).catch(e => console.error("WASM ImageMagick failed: ", e));
}

self.onmessage = async function (e) {
    const { taskId, action, payload } = e.data;
    
    try {
        let result;
        switch (action) {
            case 'SQLITE_EXEC':
                result = await handleSqlite(payload);
                break;
            case 'DUCKDB_QUERY':
                result = duckDbInstance 
                    ? await duckDbInstance.query(payload.query)
                    : await handleDuckDbFallback(payload);
                break;
            case 'PDF_RENDER':
                result = await handlePdfium(payload);
                break;
            case 'OCR_EXTRACT':
                result = tesseractInstance 
                    ? await tesseractInstance.recognize(payload.buffer)
                    : await handleOcrFallback(payload);
                break;
            case 'FILE_COMPRESS':
                result = await handleFileCompression(payload);
                break;
            case 'MEDIA_TRANSCODE':
                result = ffmpegInstance 
                    ? await ffmpegInstance.transcode(payload.buffer, payload.targetFormat)
                    : await handleFfmpegFallback(payload);
                break;
            case 'IMAGE_RESIZE':
                result = await handleImageMagick(payload);
                break;
            case 'RDP_CONNECT':
                result = guacamoleInstance 
                    ? await guacamoleInstance.connectRdp(payload.ip, payload.username)
                    : await handleRdpFallback(payload);
                break;
            case 'P2P_SEND':
                result = webTorrentInstance 
                    ? await webTorrentInstance.shareFile(payload.buffer, payload.fileName)
                    : await handleWebTorrentFallback(payload);
                break;
            case 'AI_INTENT':
                result = transformersInstance 
                    ? await transformersInstance.processIntent(payload.text)
                    : await handleAiIntentFallback(payload);
                break;
            default:
                throw new Error(`Unknown action: ${action}`);
        }
        
        self.postMessage({ taskId, status: 'success', result });
    } catch (error) {
        self.postMessage({ taskId, status: 'error', error: error.message });
    }
};

// --- SQLite Core Integration (WASM backend) ---
async function handleSqlite(payload) {
    if (!sqliteInstance) {
        throw new Error("SQLite WASM Core is not initialized yet.");
    }
    console.log("SQLite WASM Core Executing: ", payload);
    if (payload.op === 'insert') {
        const id = sqliteInstance.insertRecord(payload.key || "", payload.value || "");
        return { status: 'inserted', id, message: "Record inserted via C++ SQLiteCore." };
    } else if (payload.op === 'query') {
        const val = sqliteInstance.queryRecord(payload.key || "");
        return { status: 'success', value: val, message: "Record queried via C++ SQLiteCore." };
    } else {
        const jsonStr = sqliteInstance.getAllRecordsJson();
        return { status: 'success', records: JSON.parse(jsonStr), message: "All records fetched via C++ SQLiteCore." };
    }
}

// --- Fallback Handlers ---
async function handleDuckDbFallback(payload) {
    console.log("Executing DuckDB fallback: ", payload.query);
    return { columns: [], rows: [], message: "DuckDB engine offline." };
}

async function handlePdfium(payload) {
    if (!pdfiumInstance) {
        throw new Error("PDFium WASM Core is not initialized yet.");
    }
    console.log("PDFium rendering page: ", payload.pageIndex);
    if (payload.op === 'load') {
        pdfiumInstance.loadDocument(payload.name || "untitled.pdf", payload.pages || 1);
        return { status: 'loaded', pages: pdfiumInstance.getPageCount(), documentName: pdfiumInstance.getDocumentName() };
    }
    const width = payload.width || 800;
    const height = payload.height || 1100;
    const rgbaString = pdfiumInstance.renderPageToRgba(payload.pageIndex || 0, width, height);
    const pixels = new Uint8ClampedArray(rgbaString.length);
    for (let i = 0; i < rgbaString.length; i++) {
        pixels[i] = rgbaString.charCodeAt(i);
    }
    return {
        width: width,
        height: height,
        pixels: pixels,
        message: "PDF Page rendered successfully by WASM PDFiumCore."
    };
}

async function handleOcrFallback(payload) {
    return { text: "", confidence: 0.0, error: "OCR engine offline." };
}

async function handleFileCompression(payload) {
    if (!libarchiveInstance || !libarchiveModule) {
        throw new Error("Libarchive WASM Core is not initialized yet.");
    }
    console.log("Libarchive compressing/decompressing payload via WASM...");
    if (payload.op === 'extract') {
        const fileList = libarchiveInstance.extractArchive(payload.archiveData || "");
        const parsedFiles = JSON.parse(fileList);
        return {
            archiveSize: (payload.archiveData || "").length,
            filesCount: parsedFiles.length,
            files: parsedFiles,
            message: "Archive extracted successfully by WASM LibarchiveCore."
        };
    } else {
        const fileNames = payload.fileNames || ["document.pdf", "image.png", "notes.txt"];
        const vectorStr = new libarchiveModule.VectorString();
        for (const name of fileNames) {
            vectorStr.push_back(name);
        }
        const archiveData = libarchiveInstance.compressFiles(vectorStr);
        vectorStr.delete();
        return {
            archiveSize: archiveData.length,
            filesCount: fileNames.length,
            message: "Files compressed successfully by WASM LibarchiveCore."
        };
    }
}

async function handleFfmpegFallback(payload) {
    return { durationSec: 0, message: "FFmpeg engine offline." };
}

async function handleImageMagick(payload) {
    if (!imagemagickInstance) {
        throw new Error("ImageMagick WASM Core is not initialized yet.");
    }
    console.log("ImageMagick resizing to: ", payload.width, "x", payload.height);
    const targetW = payload.width || 100;
    const targetH = payload.height || 100;
    const outputBuffer = imagemagickInstance.resizeImage(payload.buffer || "MOCK_RAW_BUFFER", targetW, targetH);
    return {
        width: targetW,
        height: targetH,
        status: "resized",
        bufferSize: outputBuffer.length,
        message: "Image resized successfully by WASM ImageMagickCore."
    };
}

async function handleRdpFallback(payload) {
    return { error: "RDP engine offline." };
}

async function handleWebTorrentFallback(payload) {
    return { error: "P2P engine offline." };
}

async function handleAiIntentFallback(payload) {
    const text = payload.text.toLowerCase();
    if (text.includes("open") || text.includes("launch")) {
        if (text.includes("file") || text.includes("nautilus")) {
            return { intent: "LAUNCH_APP", app: "files", confidence: 0.95 };
        }
        if (text.includes("setting") || text.includes("gnome")) {
            return { intent: "LAUNCH_APP", app: "settings", confidence: 0.95 };
        }
    }
    return { intent: "GENERAL_QUERY", text: text };
}
