let hashWorker = null;
let selectedFileData = null;
let selectedFileName = "";
let currentDirectoryKey = "";

// Navigation history
let navigationHistory = [];
let historyIndex = -1;

document.addEventListener("DOMContentLoaded", function() {
    initializeAnodyneIPCBridge();
    initializeHashWorker();
    initializeDragAndDrop();
    // Default to user home view
    navigateTo('user-home');
});

let activeJobId = "";

// 1. Host QWebChannel Synchronization
function initializeAnodyneIPCBridge() {
    if (typeof qt !== 'undefined' && qt.webChannelTransport) {
        new QWebChannel(qt.webChannelTransport, function(channel) {
            window.sysContext = channel.objects.sysContext;
            sysContext.logWebEvent("Files PWA: Channel bridge synchronized.");

            sysContext.nativeJobProgressChanged.connect(function(jobId, progress) {
                if (activeJobId !== jobId) {
                    activeJobId = jobId;
                    document.getElementById("btn-pause").classList.remove("hidden");
                    document.getElementById("btn-resume").classList.add("hidden");
                }
                updateFooterTaskBar(jobId, progress);
            });

            sysContext.nativeJobFinished.connect(function(jobId, success, message) {
                completeFooterTaskBar(jobId, success, message);
            });
        });
    } else if (window.sysContext) {
        sysContext.logWebEvent("Files PWA: Android bridge synchronized.");
        
        window.onNativeJobProgressChanged = function(jobId, progress) {
            if (activeJobId !== jobId) {
                activeJobId = jobId;
                document.getElementById("btn-pause").classList.remove("hidden");
                document.getElementById("btn-resume").classList.add("hidden");
            }
            updateFooterTaskBar(jobId, progress);
        };
        
        window.onNativeJobFinished = function(jobId, success, message) {
            completeFooterTaskBar(jobId, success, message);
        };
    }
}

// 2. Directory Navigation Engine (Mocking namespaces)
const fileSystemData = {
    'system-root': {
        name: 'Other Locations',
        path: '/',
        items: [
            { name: 'bin', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'boot', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'etc', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'lib', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'var', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'initrd.img', type: 'File', size: '28.4 MB', perms: '-rw-r--r--' },
            { name: 'vmlinuz', type: 'File', size: '8.2 MB', perms: '-rw-r--r--' }
        ]
    },
    'user-home': {
        name: 'Home',
        path: '/home/user',
        items: [
            { name: 'Downloads', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'Documents', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'Pictures', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: '.config', type: 'Directory', size: '--', perms: 'drwx------' },
            { name: 'welcome.txt', type: 'File', size: '1.2 KB', perms: '-rw-r--r--' }
        ]
    },
    'external-usb': {
        name: 'External USB',
        path: '/media/usb/backup',
        items: [
            { name: 'SystemBackup_2026.tar.gz', type: 'Compressed Archive', size: '142.6 MB', perms: '-rw-r--r--' }
        ]
    },
    'recycle-bin': {
        name: 'Trash',
        path: '/root/.recycle_bin',
        items: [
            { name: 'old_kernel_config.bak', type: 'Backup File', size: '12 KB', perms: '-rw-r--r--' }
        ]
    }
};

function navigateTo(key) {
    if (!fileSystemData[key]) return;
    
    // Add to history
    if (historyIndex === -1 || navigationHistory[historyIndex] !== key) {
        navigationHistory = navigationHistory.slice(0, historyIndex + 1);
        navigationHistory.push(key);
        historyIndex = navigationHistory.length - 1;
    }
    
    switchDirectory(key);
}

function historyBack() {
    if (historyIndex > 0) {
        historyIndex--;
        switchDirectory(navigationHistory[historyIndex]);
    }
}

function historyForward() {
    if (historyIndex < navigationHistory.length - 1) {
        historyIndex++;
        switchDirectory(navigationHistory[historyIndex]);
    }
}

function updateHistoryButtons() {
    const backBtn = document.getElementById("btn-back");
    const forwardBtn = document.getElementById("btn-forward");
    
    if (backBtn) backBtn.style.opacity = (historyIndex > 0) ? "1" : "0.4";
    if (forwardBtn) forwardBtn.style.opacity = (historyIndex < navigationHistory.length - 1) ? "1" : "0.4";
}

function switchDirectory(key) {
    currentDirectoryKey = key;
    const data = fileSystemData[key];
    if (!data) return;

    updateHistoryButtons();

    // Update Sidebar visual highlights
    const listItems = document.querySelectorAll(".files-sidebar li");
    listItems.forEach(li => li.classList.remove("active"));
    
    const activeItem = document.getElementById("sb-" + key);
    if (activeItem) {
        activeItem.classList.add("active");
    }

    // Draw Breadcrumbs
    buildBreadcrumbs(data.path, key);

    // Populate file grid
    const grid = document.getElementById("files-grid");
    grid.innerHTML = "";

    data.items.forEach(item => {
        const itemEl = document.createElement("div");
        itemEl.className = "grid-item";
        
        if (item.type === 'Directory') {
            itemEl.ondblclick = function() {
                // Nautilus double click navigation simulation
                if (key === 'user-home' && (item.name === 'Downloads' || item.name === 'Documents' || item.name === 'Pictures')) {
                    // Navigate to locations or simulate interior view
                }
            };
        }
        
        itemEl.onclick = function() {
            document.querySelectorAll(".grid-item").forEach(el => el.classList.remove("selected"));
            itemEl.classList.add("selected");
            selectVirtualFile(item.name, item.type, item.size, item.perms);
        };
        
        // Clean vector icon drawings
        const iconHtml = item.type === 'Directory' ? `
            <svg width="48" height="48" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M6 10C6 7.79086 7.79086 6 10 6H24L30 14H54C56.2091 14 58 15.7909 58 18V50C58 52.2091 56.2091 54 54 54H10C7.79086 54 6 52.2091 6 50V10Z" fill="#3584e4"/>
              <path d="M6 18C6 15.7909 7.79086 14 10 14H54C56.2091 14 58 15.7909 58 18V50C58 52.2091 56.2091 54 54 54H10C7.79086 54 6 52.2091 6 50V18Z" fill="#62a0ea"/>
              <path d="M10 14H54C55.1046 14 56 14.8954 56 16V18H8V16C8 14.8954 8.89543 14 10 14Z" fill="#1c71d8" opacity="0.3"/>
            </svg>
        ` : `
            <svg width="48" height="48" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 6C12 3.79086 13.7909 2 16 2H40L52 14V58C52 60.2091 50.2091 62 48 62H16C13.7909 62 12 60.2091 12 58V6Z" fill="#e2e8f0"/>
              <path d="M40 2V14H52L40 2Z" fill="#cbd5e1"/>
              <rect x="20" y="24" width="24" height="2" rx="1" fill="#94a3b8"/>
              <rect x="20" y="32" width="24" height="2" rx="1" fill="#94a3b8"/>
              <rect x="20" y="40" width="16" height="2" rx="1" fill="#94a3b8"/>
            </svg>
        `;

        itemEl.innerHTML = `
            <div class="item-icon">${iconHtml}</div>
            <div class="item-name" title="${item.name}">${item.name}</div>
        `;
        grid.appendChild(itemEl);
    });
}

function buildBreadcrumbs(path, key) {
    const breadcrumbBox = document.getElementById("nautilus-breadcrumbs");
    if (!breadcrumbBox) return;

    breadcrumbBox.innerHTML = "";

    const segments = path.split('/').filter(s => s.length > 0);
    
    // Root Segment
    const rootItem = document.createElement("span");
    rootItem.className = "breadcrumb-item" + (segments.length === 0 ? " active" : "");
    rootItem.textContent = key === "system-root" ? "Computer" : "Home";
    rootItem.onclick = () => navigateTo(key);
    breadcrumbBox.appendChild(rootItem);

    let currentAccumulatedPath = "";
    segments.forEach((seg, idx) => {
        const sep = document.createElement("span");
        sep.className = "breadcrumb-separator";
        sep.textContent = " › ";
        breadcrumbBox.appendChild(sep);

        const item = document.createElement("span");
        const isActive = (idx === segments.length - 1);
        item.className = "breadcrumb-item" + (isActive ? " active" : "");
        item.textContent = seg;
        breadcrumbBox.appendChild(item);
    });
}

function deleteFile(event, dirKey, itemName) {
    event.stopPropagation();
    
    const dir = fileSystemData[dirKey];
    const index = dir.items.findIndex(item => item.name === itemName);
    if (index !== -1) {
        const item = dir.items.splice(index, 1)[0];
        
        fileSystemData['recycle-bin'].items.push({
            name: item.name + "_" + Date.now().toString().slice(-4),
            type: item.type,
            size: item.size,
            perms: item.perms
        });
        
        if (window.sysContext) {
            sysContext.logWebEvent("Deleted file (moved to recycle bin): " + dir.path + "/" + itemName);
            sysContext.executeSystemCommand("mv " + dir.path + "/" + itemName + " /root/.recycle_bin/");
        }
        
        switchDirectory(dirKey);
    }
}

function controlJob(action) {
    if (window.sysContext && activeJobId) {
        sysContext.jobControl(activeJobId, action);
        if (action === 'pause') {
            document.getElementById("btn-pause").classList.add("hidden");
            document.getElementById("btn-resume").classList.remove("hidden");
            document.getElementById("footer-status-msg").textContent = "Job paused.";
        } else if (action === 'resume') {
            document.getElementById("btn-resume").classList.add("hidden");
            document.getElementById("btn-pause").classList.remove("hidden");
            document.getElementById("footer-status-msg").textContent = "Writing file blocks asynchronously...";
        } else if (action === 'cancel') {
            document.getElementById("footer-status-msg").textContent = "Canceling job...";
        }
    }
}

function triggerBackupJob() {
    if (window.sysContext) {
        sysContext.executeSystemCommand("files");
    } else {
        alert("Standalone Mode: QWebChannel not connected. Cannot start C++ worker.");
    }
}

function updateFooterTaskBar(jobId, progress) {
    const footer = document.getElementById("files-telemetry-footer");
    footer.classList.remove("hidden");

    document.getElementById("footer-job-id").textContent = "ID: " + jobId;
    document.getElementById("footer-percentage-text").textContent = progress + "% Completed";
    document.getElementById("footer-progress-fill").style.width = progress + "%";
    document.getElementById("footer-status-msg").textContent = "Writing file blocks asynchronously...";
}

function completeFooterTaskBar(jobId, success, message) {
    document.getElementById("footer-percentage-text").textContent = success ? "✓ Finished" : "✗ Failed";
    document.getElementById("footer-status-msg").textContent = message;
    
    const progressFill = document.getElementById("footer-progress-fill");
    progressFill.style.width = "100%";
    progressFill.style.backgroundColor = success ? "#4caf50" : "#f44336";

    setTimeout(function() {
        document.getElementById("files-telemetry-footer").classList.add("hidden");
        progressFill.style.width = "0%";
        progressFill.style.backgroundColor = "#007acc";
        activeJobId = "";
    }, 3000);
}

function initializeHashWorker() {
    const statusEl = document.getElementById("hash-status");
    if (!statusEl) return;
    
    try {
        hashWorker = new Worker("hash_worker.js");
        hashWorker.onmessage = function(event) {
            const data = event.data;
            if (data.status === "ready") {
                statusEl.textContent = "✓ WASM engine active";
                statusEl.style.color = "#4caf50";
            } else if (data.status === "success") {
                statusEl.textContent = "✓ Calculation complete";
                statusEl.style.color = "#4caf50";
                
                document.getElementById("hash-result-box").classList.remove("hidden");
                document.getElementById("hash-value").value = data.hash;
                document.getElementById("hash-duration-val").textContent = data.duration;
                document.getElementById("btn-calculate-hash").disabled = false;
            } else if (data.status === "error") {
                statusEl.textContent = "❌ Initialization error";
                statusEl.style.color = "#f44336";
                console.error(data.error);
            }
        };
        hashWorker.onerror = function(err) {
            statusEl.textContent = "❌ Load error";
            statusEl.style.color = "#f44336";
            console.error(err);
        };
    } catch (e) {
        statusEl.textContent = "❌ Web Workers unsupported";
        statusEl.style.color = "#f44336";
        console.error(e);
    }
}

function selectVirtualFile(name, type, size, perms) {
    document.getElementById("details-empty-state").classList.add("hidden");
    document.getElementById("details-active-state").classList.remove("hidden");
    
    document.getElementById("detail-name").textContent = name;
    document.getElementById("detail-type").textContent = type;
    document.getElementById("detail-size").textContent = size;
    document.getElementById("detail-perms").firstElementChild.textContent = perms;
    
    document.getElementById("hash-result-box").classList.add("hidden");
    document.getElementById("hash-value").value = "";
    
    const calcBtn = document.getElementById("btn-calculate-hash");
    const statusEl = document.getElementById("hash-status");
    
    if (type === "Directory") {
        calcBtn.disabled = true;
        calcBtn.style.opacity = 0.5;
        statusEl.textContent = "Cannot compute checksum for directory";
        statusEl.style.color = "#a0a0a0";
        selectedFileData = null;
    } else {
        calcBtn.disabled = false;
        calcBtn.style.opacity = 1;
        statusEl.textContent = "Ready to hash virtual payload";
        statusEl.style.color = "#ffcc00";
        
        const mockContent = `Anodyne OS Mock File Payload for: ${name} (${size}) permissions: ${perms}`;
        const encoder = new TextEncoder();
        selectedFileData = encoder.encode(mockContent).buffer;
        selectedFileName = name;
    }
}

function initializeDragAndDrop() {
    const dropzone = document.getElementById("details-sidebar");
    const emptyState = document.getElementById("details-empty-state");
    
    if (!dropzone) return;
    
    ['dragenter', 'dragover'].forEach(eventName => {
        dropzone.addEventListener(eventName, (e) => {
            e.preventDefault();
            e.stopPropagation();
            emptyState.classList.add("dragover");
        }, false);
    });
    
    ['dragleave', 'drop'].forEach(eventName => {
        dropzone.addEventListener(eventName, (e) => {
            e.preventDefault();
            e.stopPropagation();
            emptyState.classList.remove("dragover");
        }, false);
    });
    
    dropzone.addEventListener('drop', (e) => {
        const dt = e.dataTransfer;
        const files = dt.files;
        if (files.length > 0) {
            handleDroppedFile(files[0]);
        }
    }, false);
}

function handleDroppedFile(file) {
    document.getElementById("details-empty-state").classList.add("hidden");
    document.getElementById("details-active-state").classList.remove("hidden");
    
    document.getElementById("detail-name").textContent = file.name;
    document.getElementById("detail-type").textContent = file.type || "Local File";
    
    let sizeStr = "";
    if (file.size < 1024) sizeStr = file.size + " B";
    else if (file.size < 1024 * 1024) sizeStr = (file.size / 1024).toFixed(1) + " KB";
    else sizeStr = (file.size / (1024 * 1024)).toFixed(1) + " MB";
    
    document.getElementById("detail-size").textContent = sizeStr;
    document.getElementById("detail-perms").firstElementChild.textContent = "-rw-r--r-- (local)";
    
    document.getElementById("hash-result-box").classList.add("hidden");
    document.getElementById("hash-value").value = "";
    
    const calcBtn = document.getElementById("btn-calculate-hash");
    const statusEl = document.getElementById("hash-status");
    
    calcBtn.disabled = true;
    statusEl.textContent = "Reading local file...";
    statusEl.style.color = "#ffcc00";
    
    const reader = new FileReader();
    reader.onload = function(e) {
        selectedFileData = e.target.result;
        selectedFileName = file.name;
        calcBtn.disabled = false;
        statusEl.textContent = "Ready to hash local file";
        statusEl.style.color = "#ffcc00";
    };
    reader.onerror = function() {
        statusEl.textContent = "❌ Failed to read file";
        statusEl.style.color = "#f44336";
    };
    reader.readAsArrayBuffer(file);
}

function calculateFileHash() {
    if (!hashWorker || !selectedFileData) return;
    
    const statusEl = document.getElementById("hash-status");
    statusEl.textContent = "⚡ Computing SHA-256 in background worker...";
    statusEl.style.color = "#ffcc00";
    document.getElementById("btn-calculate-hash").disabled = true;
    
    hashWorker.postMessage({ fileData: selectedFileData });
}
