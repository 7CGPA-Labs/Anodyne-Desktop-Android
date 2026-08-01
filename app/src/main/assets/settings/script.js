document.addEventListener("DOMContentLoaded", function() {
    initializeAnodyneIPCBridge();
    setupSettingListeners();
    switchCategory('display');
});

function initializeAnodyneIPCBridge() {
    if (window.sysContext) {
        console.log("Bridge synchronized. Connected to Android host container.");
        loadHostSystemStats();
    } else {
        console.log("Standalone Mode: Android host bridge not present. Running local simulated preferences.");
    }
}

function loadHostSystemStats() {
    if (window.sysContext && typeof window.sysContext.getAccessPin === 'function') {
        const pin = window.sysContext.getAccessPin();
        document.getElementById("remote-pin-val").textContent = pin;
    }
}

function switchCategory(cat) {
    const sections = document.querySelectorAll(".settings-section");
    sections.forEach(sec => sec.classList.add("hidden"));

    const activeSec = document.getElementById("sec-" + cat);
    if (activeSec) {
        activeSec.classList.remove("hidden");
    }

    const titles = {
        'display': 'Display & Cast',
        'legibility': 'Legibility & Text',
        'input': 'Mouse & Keyboard',
        'remote': 'Remote Help',
        'storage': 'Storage & Drafts',
        'web-apps': 'Default Web Apps'
    };
    document.getElementById("category-title").textContent = titles[cat] || 'Settings';

    const listItems = document.querySelectorAll(".category-list li");
    listItems.forEach(li => li.classList.remove("active"));
    
    const activeLi = document.getElementById("cat-" + cat);
    if (activeLi) {
        activeLi.classList.add("active");
    }

    loadHostSystemStats();
}

function setupSettingListeners() {
    // Read cached values from localStorage
    const cachedOverscan = localStorage.getItem("overscan") || 0;
    const overscanSlider = document.getElementById("overscan-slider");
    if (overscanSlider) {
        overscanSlider.value = cachedOverscan;
        document.getElementById("overscan-val").textContent = cachedOverscan + "px";
    }

    const cachedSpeed = localStorage.getItem("pointerSpeed") || 10;
    const speedSlider = document.getElementById("pointer-speed-slider");
    if (speedSlider) {
        speedSlider.value = cachedSpeed;
        document.getElementById("pointer-speed-val").textContent = (cachedSpeed / 10).toFixed(1) + "x";
    }
}

// Wireless displays simulation triggers
function scanWifiDisplays() {
    const row = document.getElementById("display-list-row");
    row.style.display = row.style.display === "none" ? "flex" : "none";
}

function connectWifiDisplay(name) {
    alert(`Connecting wirelessly to: ${name}...`);
}

function changeOverscan(val) {
    document.getElementById("overscan-val").textContent = val + "px";
    localStorage.setItem("overscan", val);
    if (window.sysContext && typeof window.sysContext.setOverscanPadding === 'function') {
        window.sysContext.setOverscanPadding(parseInt(val, 10));
    }
}

function changeDisplayMode(mode) {
    if (window.sysContext && typeof window.sysContext.logWebEvent === 'function') {
        window.sysContext.logWebEvent(`Settings: Changed display mode to ${mode}`);
    }
}

function changeWallpaper(wallpaper) {
    if (window.sysContext && typeof window.sysContext.logWebEvent === 'function') {
        window.sysContext.logWebEvent(`Settings: Updated desktop wallpaper theme to ${wallpaper}`);
    }
}

function changeScale(scaleStr) {
    const scale = parseFloat(scaleStr);
    if (window.sysContext && typeof window.sysContext.setUiScale === 'function') {
        window.sysContext.setUiScale(scale);
    }
}

function toggleHighContrast(checked) {
    if (checked) {
        document.body.classList.add("high-contrast");
    } else {
        document.body.classList.remove("high-contrast");
    }
    if (window.sysContext && typeof window.sysContext.logWebEvent === 'function') {
        window.sysContext.logWebEvent(`Settings: Toggle high contrast mode to ${checked}`);
    }
}

function changeCursorStyle() {
    const color = document.getElementById("cursor-color").value;
    const size = document.getElementById("cursor-size").value;
    if (window.sysContext && typeof window.sysContext.setCursorStyle === 'function') {
        window.sysContext.setCursorStyle(color, size);
    }
}

function changePointerSpeed(val) {
    const factor = (val / 10).toFixed(1);
    document.getElementById("pointer-speed-val").textContent = factor + "x";
    localStorage.setItem("pointerSpeed", val);
    if (window.sysContext && typeof window.sysContext.setPointerSpeed === 'function') {
        window.sysContext.setPointerSpeed(parseFloat(factor));
    }
}

function changeScrollDirection(natural) {
    if (window.sysContext && typeof window.sysContext.setScrollDirectionNatural === 'function') {
        window.sysContext.setScrollDirectionNatural(natural);
    }
}

function triggerHelpPin() {
    const row = document.getElementById("remote-pin-row");
    row.style.display = "flex";
    loadHostSystemStats();
}

function toggleUnattended(checked) {
    const row = document.getElementById("unattended-pass-row");
    row.style.display = checked ? "flex" : "none";
}

function disconnectRemoteSession() {
    if (window.sysContext && typeof window.sysContext.stopRemoteControlSession === 'function') {
        window.sysContext.stopRemoteControlSession();
    }
    document.getElementById("remote-pin-row").style.display = "none";
    document.getElementById("disconnect-remote-btn").style.display = "none";
    document.getElementById("connection-status-label").textContent = "No active connections";
}

function triggerCacheCleanup() {
    if (window.sysContext && typeof window.sysContext.clearSystemStorage === 'function') {
        window.sysContext.clearSystemStorage();
    } else {
        alert("Cleanup Simulated: System temporary databases and cache cleared successfully!");
    }
}
