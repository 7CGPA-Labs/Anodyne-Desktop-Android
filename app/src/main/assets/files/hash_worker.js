// Web Worker for background WASM file hashing
importScripts("sha256_wasm.js");

Module().then(instance => {
    self.onmessage = function(event) {
        const { fileData } = event.data;
        const uint8Data = new Uint8Array(fileData);
        const len = uint8Data.length;
        
        // Allocate memory in WASM heap
        const dataPtr = instance._malloc(len);
        const outputPtr = instance._malloc(32); // SHA-256 output is 32 bytes
        
        // Copy byte array into WASM heap
        instance.HEAPU8.set(uint8Data, dataPtr);
        
        // Measure execution time
        const startTime = performance.now();
        instance._compute_sha256(dataPtr, len, outputPtr);
        const endTime = performance.now();
        const duration = (endTime - startTime).toFixed(2);
        
        // Read hash bytes from WASM heap
        const hashBytes = new Uint8Array(instance.HEAPU8.buffer, outputPtr, 32);
        
        // Convert to Hex string
        let hexString = "";
        for (let i = 0; i < 32; i++) {
            let hex = hashBytes[i].toString(16);
            if (hex.length === 1) hex = "0" + hex;
            hexString += hex;
        }
        
        // Free allocated heap memory
        instance._free(dataPtr);
        instance._free(outputPtr);
        
        self.postMessage({
            status: "success",
            hash: hexString,
            duration: duration
        });
    };
    
    // Notify the UI thread that the WASM module is loaded and ready
    self.postMessage({ status: "ready" });
}).catch(err => {
    self.postMessage({ status: "error", error: err.toString() });
});
