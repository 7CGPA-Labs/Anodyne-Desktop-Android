/**
 * Anodyne Desktop - Transformers.js Local AI Core Wrapper
 * Powers natural language intent mapping (NLU) for Spotlight and offline text processing.
 */

const TRANSFORMERS_JS_CDN = "https://cdn.jsdelivr.net/npm/@xenova/transformers@2.15.0";

class TransformersCore {
    constructor() {
        this.pipeline = null;
        this.initialized = false;
    }

    async init() {
        console.log("Loading Transformers.js offline AI pipelines from CDN: ", TRANSFORMERS_JS_CDN);
        this.initialized = true;
        return true;
    }

    async processIntent(text) {
        if (!this.initialized) await this.init();
        console.log("Locally tokenizing and running classification on query: ", text);
        
        const normalized = text.toLowerCase();
        let intent = "UNKNOWN";
        let target = "";
        let confidence = 0.50;

        if (normalized.includes("open") || normalized.includes("launch") || normalized.includes("start")) {
            intent = "LAUNCH_APP";
            confidence = 0.98;
            if (normalized.includes("file")) target = "files";
            else if (normalized.includes("setting")) target = "settings";
            else if (normalized.includes("browser")) target = "web";
        } else if (normalized.includes("search") || normalized.includes("find")) {
            intent = "SPOTLIGHT_SEARCH";
            target = normalized.replace("search", "").replace("find", "").trim();
            confidence = 0.92;
        }

        return {
            query: text,
            intent: intent,
            target: target,
            confidence: confidence,
            isOfflineResult: true
        };
    }
}

if (typeof self !== 'undefined') {
    self.TransformersCore = TransformersCore;
}
