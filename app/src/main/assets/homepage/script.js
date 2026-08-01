document.addEventListener("DOMContentLoaded", function() {
    initializeClock();
    initializeAnodyneIPCBridge();
    initializeFocus();
    initializeQuote();
    renderRssFeed();
});

// 1. Live Clock & Greeting
function initializeClock() {
    const clockElement = document.getElementById("live-clock");
    const greetingElement = document.getElementById("user-greeting");

    function updateTime() {
        const now = new Date();
        // Format to hh:mm (Momentum standard)
        let hours = now.getHours();
        let minutes = now.getMinutes();
        if (minutes < 10) minutes = "0" + minutes;
        
        clockElement.textContent = hours + ":" + minutes;
        
        if (hours < 12) greetingElement.textContent = "Good morning, Gagan.";
        else if (hours < 17) greetingElement.textContent = "Good afternoon, Gagan.";
        else greetingElement.textContent = "Good evening, Gagan.";
    }
    
    updateTime();
    setInterval(updateTime, 1000);
}

// 2. Interactive Main Focus Manager
function initializeFocus() {
    const focusInput = document.getElementById("focus-input");
    const focusDisplay = document.getElementById("focus-display");
    const focusPrompt = document.getElementById("focus-prompt");
    const focusText = document.getElementById("focus-text");
    const focusCheckbox = document.getElementById("focus-checkbox");

    // Check if focus is already saved
    const savedFocus = localStorage.getItem("momentum-focus");
    const savedStatus = localStorage.getItem("momentum-focus-status") === "true";

    if (savedFocus) {
        displayFocus(savedFocus, savedStatus);
    }

    focusInput.addEventListener("keydown", function(event) {
        if (event.key === "Enter" && focusInput.value.trim() !== "") {
            const focusVal = focusInput.value.trim();
            localStorage.setItem("momentum-focus", focusVal);
            localStorage.setItem("momentum-focus-status", "false");
            displayFocus(focusVal, false);
        }
    });
}

function displayFocus(text, isComplete) {
    const focusInput = document.getElementById("focus-input");
    const focusDisplay = document.getElementById("focus-display");
    const focusPrompt = document.getElementById("focus-prompt");
    const focusText = document.getElementById("focus-text");
    const focusCheckbox = document.getElementById("focus-checkbox");

    focusInput.classList.add("hidden");
    focusPrompt.classList.add("hidden");
    focusDisplay.classList.remove("hidden");
    
    focusText.textContent = text;
    focusCheckbox.checked = isComplete;
}

function toggleFocusComplete(checked) {
    localStorage.setItem("momentum-focus-status", checked ? "true" : "false");
}

function clearFocus() {
    localStorage.removeItem("momentum-focus");
    localStorage.removeItem("momentum-focus-status");

    const focusInput = document.getElementById("focus-input");
    const focusDisplay = document.getElementById("focus-display");
    const focusPrompt = document.getElementById("focus-prompt");

    focusInput.value = "";
    focusInput.classList.remove("hidden");
    focusPrompt.classList.remove("hidden");
    focusDisplay.classList.add("hidden");
}

// 3. Rotating Quotes
function initializeQuote() {
    const quotes = [
        "\"Focus on being productive instead of busy.\"",
        "\"One way to keep momentum going is to have constantly greater goals.\"",
        "\"The secret of getting ahead is getting started.\"",
        "\"Your mind is for having ideas, not holding them.\"",
        "\"Simplicity is the ultimate sophistication.\"",
        "\"Do not let what you cannot do interfere with what you can do.\""
    ];
    const randomQuote = quotes[Math.floor(Math.random() * quotes.length)];
    document.getElementById("quote-text").textContent = randomQuote;
}

// 4. Panel Widgets Controls
function toggleWidget(id) {
    const target = document.getElementById(id);
    const allPanels = document.querySelectorAll(".translucent-panel");
    
    const isHidden = target.classList.contains("hidden");
    
    // Hide all first
    allPanels.forEach(panel => panel.classList.add("hidden"));
    
    if (isHidden) {
        target.classList.remove("hidden");
    }
}

// 5. Host IPC Bridge Link
function initializeAnodyneIPCBridge() {
    if (typeof qt !== 'undefined' && qt.webChannelTransport) {
        new QWebChannel(qt.webChannelTransport, function(channel) {
            window.sysContext = channel.objects.sysContext;
            sysContext.logWebEvent("Dashboard UI successfully integrated with Hard Layer.");

            // Connect asynchronous task telemetry
            sysContext.nativeJobProgressChanged.connect(function(jobId, progress) {
                updateVisualTaskBar(jobId, progress);
            });

            sysContext.nativeJobFinished.connect(function(jobId, success, message) {
                completeVisualTaskBar(jobId, message);
            });
        });
    } else if (window.sysContext) {
        sysContext.logWebEvent("Dashboard UI successfully integrated with Android Hard Layer.");
        
        // Define global callbacks for Android to trigger
        window.onNativeJobProgressChanged = function(jobId, progress) {
            updateVisualTaskBar(jobId, progress);
        };
        window.onNativeJobFinished = function(jobId, success, message) {
            completeVisualTaskBar(jobId, message);
        };

        if (typeof sysContext.getGpsLocation === 'function') {
            const loc = sysContext.getGpsLocation();
            window.updateDashboardGpsLocation(loc);
        }
    }
}

window.updateDashboardGpsLocation = function(placeString) {
    const el = document.getElementById("gps-place-text");
    if (el) el.textContent = placeString;
};

function launchApp(appKeyword) {
    if (window.sysContext) {
        if (appKeyword === 'files' || appKeyword === 'settings') {
            sysContext.executeSystemCommand(appKeyword);
        } else {
            sysContext.launchApp(appKeyword);
        }
    }
}

// Telemetry visual updates
function updateVisualTaskBar(jobId, progress) {
    // Open task widget automatically to show progress
    document.getElementById("task-panel").classList.remove("hidden");
    document.getElementById("no-tasks-msg").classList.add("hidden");
    
    const container = document.getElementById("active-task-container");
    container.classList.remove("hidden");

    document.getElementById("task-id").textContent = "ID: " + jobId;
    document.getElementById("task-percentage").textContent = progress + "% Processing...";
    document.getElementById("task-progress-fill").style.width = progress + "%";
}

function completeVisualTaskBar(jobId, statusMessage) {
    document.getElementById("task-percentage").textContent = "✓ Finished successfully.";
    document.getElementById("task-progress-fill").style.width = "100%";
    document.getElementById("task-progress-fill").style.backgroundColor = "#4caf50";

    setTimeout(function() {
        document.getElementById("active-task-container").classList.add("hidden");
        document.getElementById("no-tasks-msg").classList.remove("hidden");
        
        // Reset element styles back to defaults
        document.getElementById("task-progress-fill").style.width = "0%";
        document.getElementById("task-progress-fill").style.backgroundColor = "#3584e4";
    }, 3000);
}

// Mock RSS data
const mockRssData = [
    { title: "Anodyne OS Stable Synthesized", desc: "Our secure, web-first immutable appliance OS is now live with SysV init, zRAM compressed swap, and dm-verity verification hooks." },
    { title: "Gnome Style Preferences Added", desc: "Unified control panel and File Viewer structured cleanly into sidebar layouts matching Gnome desktop configurations." },
    { title: "TPM LUKS Encryption Configured", desc: "Headless installation auto partition mappings successfully binding writable user spaces to Trusted Platform Modules." }
];

function renderRssFeed() {
    const list = document.getElementById("rss-items-list");
    list.innerHTML = "";
    mockRssData.forEach(item => {
        const div = document.createElement("div");
        div.className = "rss-item";
        div.innerHTML = `
            <div class="rss-title">${item.title}</div>
            <div class="rss-desc">${item.desc}</div>
        `;
        list.appendChild(div);
    });
}


