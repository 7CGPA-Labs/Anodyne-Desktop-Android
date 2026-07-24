// Web Worker for background WASM calculation
importScripts("wasm_calc.js");

// Emscripten modularized builds expose a Module factory promise
Module().then(instance => {
    self.onmessage = function(event) {
        const n = event.data;
        
        // Measure execution time
        const startTime = performance.now();
        const result = instance._compute_fibonacci(n);
        const endTime = performance.now();
        const duration = (endTime - startTime).toFixed(2);
        
        self.postMessage({
            status: "success",
            n: n,
            result: result,
            duration: duration
        });
    };
    
    // Notify main thread that the WASM module is loaded and ready
    self.postMessage({ status: "ready" });
}).catch(err => {
    self.postMessage({ status: "error", error: err.toString() });
});
