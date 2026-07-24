let hashWorker = null;
let selectedFileData = null;
let selectedFileName = "";

document.addEventListener("DOMContentLoaded", function() {
    initializeAnodyneIPCBridge();
    initializeHashWorker();
    initializeDragAndDrop();
    // Default to user home view
    switchDirectory('user-home');
});

let activeJobId = "";

// 1. Host QWebChannel Synchronization
function initializeAnodyneIPCBridge() {
    if (typeof qt !== 'undefined' && qt.webChannelTransport) {
        new QWebChannel(qt.webChannelTransport, function(channel) {
            window.sysContext = channel.objects.sysContext;
            sysContext.logWebEvent("Files PWA: Channel bridge synchronized.");

            // Connect asynchronous job progress streams
            sysContext.nativeJobProgressChanged.connect(function(jobId, progress) {
                if (activeJobId !== jobId) {
                    activeJobId = jobId;
                    document.getElementById("btn-pause").classList.remove("hidden");
                    document.getElementById("btn-resume").classList.add("hidden");
                }
                updateFooterTaskBar(jobId, progress);
            });

            // Connect final completion notifications
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
        path: '/home/user/',
        items: [
            { name: 'Downloads', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'Documents', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'Pictures', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: '.config', type: 'Directory', size: '--', perms: 'drwx------' },
            { name: 'welcome.txt', type: 'File', size: '1.2 KB', perms: '-rw-r--r--' }
        ]
    },
    'sandbox-rootfs': {
        path: '/var/lib/anodyne/rootfs/',
        items: [
            { name: 'src', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'ui', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'web-apps', type: 'Directory', size: '--', perms: 'drwxr-xr-x' },
            { name: 'main.cpp', type: 'C++ Source', size: '1.9 KB', perms: '-rw-r--r--' },
            { name: 'AnodyneOS.pro', type: 'Project config', size: '837 B', perms: '-rw-r--r--' },
            { name: 'LICENSE', type: 'License file', size: '35.8 KB', perms: '-rw-r--r--' },
            { name: 'README.md', type: 'Markdown doc', size: '5.8 KB', perms: '-rw-r--r--' }
        ]
    },
    'external-usb': {
        path: '/media/usb/backup/',
        items: [
            { name: 'SystemBackup_2026.tar.gz', type: 'Compressed Archive', size: '142.6 MB', perms: '-rw-r--r--' }
        ]
    },
    'recycle-bin': {
        path: '/root/.recycle_bin/',
        items: [
            { name: 'old_kernel_config.bak', type: 'Backup File', size: '12 KB', perms: '-rw-r--r--' }
        ]
    }
};

function switchDirectory(key) {
    const data = fileSystemData[key];
    if (!data) return;

    // Update path label
    document.getElementById("current-path").textContent = data.path;

    // Update Sidebar visual highlights
    const listItems = document.querySelectorAll(".drive-list li");
    listItems.forEach(li => li.classList.remove("active"));
    
    const activeItem = document.getElementById("sb-" + key);
    if (activeItem) {
        activeItem.classList.add("active");
    }

    // Populate file table rows
    const tbody = document.getElementById("files-list-body");
    tbody.innerHTML = "";

    data.items.forEach(item => {
        const tr = document.createElement("tr");
        if (item.type === 'Directory') {
            tr.className = 'folder';
        }
        
        tr.onclick = function() {
            selectVirtualFile(item.name, item.type, item.size, item.perms);
        };
        
        let actionCell = "";
        if (key !== 'recycle-bin') {
            actionCell = `<td><button class="btn btn-danger btn-sm" onclick="deleteFile(event, '${key}', '${item.name}')">Delete</button></td>`;
        } else {
            actionCell = `<td><span style="color: #666; font-style: italic;">Locked</span></td>`;
        }

        tr.innerHTML = `
            <td>${item.type === 'Directory' ? '📁' : '📄'} ${item.name}</td>
            <td>${item.type}</td>
            <td>${item.size}</td>
            <td><code>${item.perms}</code></td>
            ${actionCell}
        `;
        tbody.appendChild(tr);
    });
}

function deleteFile(event, dirKey, itemName) {
    event.stopPropagation();
    
    const dir = fileSystemData[dirKey];
    const index = dir.items.findIndex(item => item.name === itemName);
    if (index !== -1) {
        const item = dir.items.splice(index, 1)[0];
        
        // Push it into recycle-bin
        fileSystemData['recycle-bin'].items.push({
            name: item.name + "_" + Date.now().toString().slice(-4),
            type: item.type,
            size: item.size,
            perms: item.perms
        });
        
        if (window.sysContext) {
            sysContext.logWebEvent("Deleted file (moved to recycle bin): " + dir.path + itemName);
            sysContext.executeSystemCommand("mv " + dir.path + itemName + " /root/.recycle_bin/");
        }
        
        // Refresh view
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

// 3. Asynchronous Job Trigger
function triggerBackupJob() {
    if (window.sysContext) {
        // executeSystemCommand("files") triggers the background copy worker in C++
        sysContext.executeSystemCommand("files");
    } else {
        alert("Standalone Mode: QWebChannel not connected. Cannot start C++ worker.");
    }
}

// 4. Telemetry UI Updates
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

    // Wait and then slide the footer out of view
    setTimeout(function() {
        document.getElementById("files-telemetry-footer").classList.add("hidden");
        // Reset styling
        progressFill.style.width = "0%";
        progressFill.style.backgroundColor = "#007acc";
        activeJobId = "";
    }, 3000);
}

// 5. WASM Checksum Hashing & Drag-and-Drop Analysis
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
    
    // Hide previous hash results
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
        
        // Generate mock binary contents for virtual files based on name/size
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
