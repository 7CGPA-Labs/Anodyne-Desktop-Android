document.addEventListener("DOMContentLoaded", function() {
    initializeAnodyneIPCBridge();
    setupSettingListeners();
    startSimulatedModemTelemetry();
    startSystemMetricsSimulation();
    switchCategory('network');
});

// 1. QWebChannel Host Synchronization
function initializeAnodyneIPCBridge() {
    if (typeof qt !== 'undefined' && qt.webChannelTransport) {
        new QWebChannel(qt.webChannelTransport, function(channel) {
            window.sysContext = channel.objects.sysContext;
            logSystemEvent("Bridge synchronized. Unified Control linked to host daemon.");
            sysContext.logWebEvent("Settings PWA: Connection interface active.");
            
            sysContext.nativeJobProgressChanged.connect(function(jobId, progress) {
                logSystemEvent(`Background worker active: Job [${jobId}] progress: ${progress}%`);
            });
            sysContext.nativeJobFinished.connect(function(jobId, success, message) {
                logSystemEvent(`Background worker completed: Job [${jobId}] Success: ${success}. Info: ${message}`);
            });
            
            loadZramMetricsFromHost();
            loadHostSystemStats();
        });
    } else if (window.sysContext) {
        logSystemEvent("Bridge synchronized. Connected to Android host container.");
        sysContext.logWebEvent("Settings PWA: Connection interface active on Android.");
        
        window.onNativeJobProgressChanged = function(jobId, progress) {
            logSystemEvent(`Background worker active: Job [${jobId}] progress: ${progress}%`);
        };
        window.onNativeJobFinished = function(jobId, success, message) {
            logSystemEvent(`Background worker completed: Job [${jobId}] Success: ${success}. Info: ${message}`);
        };
        
        loadZramMetricsFromHost();
        loadHostSystemStats();
    } else {
        logSystemEvent("Standalone Mode: QWebChannel and Android bridge not present. Running offline simulations.");
    }
}

function loadZramMetricsFromHost() {
    if (window.sysContext) {
        try {
            var size = sysContext.getZramDiskSize();
            if (typeof size === 'function' || size === undefined) {
                sysContext.getZramDiskSize(function(s) {
                    document.getElementById("zram-size-val").textContent = s;
                });
                sysContext.getZramAlgorithm(function(a) {
                    document.getElementById("zram-algo-val").textContent = a.trim();
                });
                sysContext.getSystemSwappiness(function(sw) {
                    document.getElementById("swappiness-val").textContent = sw;
                });
            } else {
                document.getElementById("zram-size-val").textContent = size;
                document.getElementById("zram-algo-val").textContent = sysContext.getZramAlgorithm();
                document.getElementById("swappiness-val").textContent = sysContext.getSystemSwappiness();
            }
        } catch (e) {
            console.error("Failed to query zRAM metrics:", e);
        }
    }
}

function loadHostSystemStats() {
    if (window.sysContext) {
        try {
            // Wi-Fi SSID connection query
            if (typeof sysContext.getWifiSSID === 'function') {
                var ssid = sysContext.getWifiSSID();
                if (ssid) {
                    document.getElementById("active-wifi-name").textContent = ssid;
                }
            }
            // Storage Capacity query
            if (typeof sysContext.getStorageStatus === 'function') {
                var storage = sysContext.getStorageStatus();
                if (storage) {
                    document.getElementById("storage-status-val").textContent = storage;
                }
            }
        } catch (e) {
            console.error("Failed to query host system stats:", e);
        }
    }
}

// 2. Setting Event Handlers
function setupSettingListeners() {
    const dataToggle = document.getElementById("mobile-data-toggle");
    if (dataToggle) {
        dataToggle.addEventListener("change", function() {
            const state = dataToggle.checked ? "ENABLED" : "DISABLED";
            logSystemEvent(`Mobile 4G Data set to ${state}`);
            if (window.sysContext) {
                sysContext.logWebEvent(`Settings: Changed Mobile Data State to ${state}`);
            }
        });
    }

    const netSelect = document.getElementById("network-type-select");
    if (netSelect) {
        netSelect.addEventListener("change", function() {
            const mode = netSelect.value.toUpperCase();
            logSystemEvent(`Preferred Network Type changed to: ${mode}`);
            if (window.sysContext) {
                sysContext.logWebEvent(`Settings: Network Type Preference set to ${mode}`);
            }
        });
    }
}

// Slider controls
function changeBrightness(val) {
    document.getElementById("brightness-val").textContent = val + "%";
    logSystemEvent(`Sysfs Brightness output -> /sys/class/backlight/brightness set to: ${val}%`);
    if (window.sysContext) {
        sysContext.logWebEvent(`Settings: sysfs backlight write -> ${val}%`);
    }
}

function changeVolume(val) {
    document.getElementById("volume-val").textContent = val + "%";
    logSystemEvent(`ALSA Sound Mixer output -> Master volume set to: ${val}%`);
    if (window.sysContext) {
        sysContext.logWebEvent(`Settings: ALSA mixer volume -> ${val}%`);
    }
}

function saveAPN() {
    const name = document.getElementById("apn-name").value;
    const address = document.getElementById("apn-address").value;
    logSystemEvent(`APN Config Updated -> Name: "${name}", APN: "${address}"`);
    if (window.sysContext) {
        sysContext.logWebEvent(`Settings: Updated APN profile to Name:${name} / APN:${address}`);
    }
}

// Power triggers
function triggerPowerAction(action) {
    const consent = confirm(`Are you sure you want to perform system action: [${action.toUpperCase()}]?`);
    if (!consent) return;

    logSystemEvent(`System Action Triggered: ${action.toUpperCase()}`);
    if (window.sysContext) {
        sysContext.logWebEvent(`Settings: Executing native power execution -> ${action}`);
        sysContext.executeSystemCommand(action);
    } else {
        logSystemEvent(`[Simulation] Host system executing: ${action}`);
    }
}

// Telemetry events
function logSystemEvent(msg) {
    const logsBox = document.getElementById("telemetry-logs");
    if (!logsBox) return;
    
    const now = new Date();
    const timestamp = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    
    const entry = document.createElement("div");
    entry.className = "log-entry";
    entry.innerHTML = `[${timestamp}] ${msg}`;
    logsBox.appendChild(entry);
    
    logsBox.scrollTop = logsBox.scrollHeight;
}

function startSimulatedModemTelemetry() {
    const signalDisplay = document.getElementById("ofono-signal");
    if (!signalDisplay) return;
    
    setInterval(function() {
        const dbs = -70 - Math.floor(Math.random() * 15);
        let quality = "Excellent";
        if (dbs < -80) quality = "Good";
        if (dbs < -83) quality = "Fair";
        signalDisplay.textContent = `${dbs} dBm (${quality})`;
    }, 8000);

    const simulatedSMS = [
        "SMS from +14155552671: 'System deployment status: OK'",
        "SMS from Carrier: 'APN profile sync succeeded.'",
        "SMS from +14155559092: 'Check out the new Anodyne workspace!'",
        "SMS from System Watchdog: 'Memory compression zRAM zstd optimized.'"
    ];

    setInterval(function() {
        const randomMsg = simulatedSMS[Math.floor(Math.random() * simulatedSMS.length)];
        logSystemEvent(randomMsg);
        if (window.sysContext) {
            sysContext.logWebEvent(`Settings (oFono Simulation): ${randomMsg}`);
        }
    }, 25000);
}

// Fluctuates CPU values
function startSystemMetricsSimulation() {
    const cpuDisplay = document.getElementById("cpu-load");
    if (!cpuDisplay) return;
    
    setInterval(function() {
        const load = 5 + Math.floor(Math.random() * 20);
        cpuDisplay.textContent = `${load}% Load`;
    }, 4000);
}

// Category switcher
function switchCategory(cat) {
    const sections = document.querySelectorAll(".settings-section");
    sections.forEach(sec => sec.classList.add("hidden"));

    const activeSec = document.getElementById("sec-" + cat);
    if (activeSec) {
        activeSec.classList.remove("hidden");
    }

    const titles = {
        'network': 'Network',
        'wifi': 'Wi-Fi',
        'bluetooth': 'Bluetooth',
        'display': 'Display & Sound',
        'security': 'Security & Privacy',
        'telemetry': 'System Telemetry',
        'about': 'About'
    };
    document.getElementById("category-title").textContent = titles[cat] || 'Settings';

    const listItems = document.querySelectorAll(".category-list li");
    listItems.forEach(li => li.classList.remove("active"));
    
    const activeLi = document.getElementById("cat-" + cat);
    if (activeLi) {
        activeLi.classList.add("active");
    }

    // Refresh Wi-Fi and Storage telemetries when visiting relevant tabs
    loadHostSystemStats();
}
