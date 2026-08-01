package com.anodyne.desktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class DesktopRemoteServer(
    private val port: Int,
    private val getPin: () -> String,
    private val getActiveWebView: () -> WebView?,
    private val handleRemoteInput: (String, Float, Float) -> Unit,
    private val onConnectionActive: (Boolean) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Custom socket HTTP server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error in server socket loop", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        executor.shutdownNow()
        Log.i(TAG, "Custom socket HTTP server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: return
            
            val parts = firstLine.split("\\s+".toRegex())
            if (parts.size < 2) {
                sendError(socket, 400, "Bad Request")
                return
            }
            
            val pathAndQuery = parts[1]
            
            // Consume headers
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null || line.isEmpty()) break
            }
            
            val uriParts = pathAndQuery.split("\\?".toRegex(), 2)
            val path = uriParts[0]
            val query = if (uriParts.size > 1) uriParts[1] else ""
            val queryParams = parseQuery(query)

            when (path) {
                "/" -> serveDashboard(socket)
                "/api/screenshot" -> serveScreenshot(socket, queryParams)
                "/api/input" -> handleInput(socket, queryParams)
                else -> sendError(socket, 404, "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling remote client connection", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {}
        }
    }

    private fun serveDashboard(socket: Socket) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <title>Anodyne Remote Desktop</title>
                <style>
                    body {
                        margin: 0;
                        background: #050508;
                        color: #f8fafc;
                        font-family: system-ui, sans-serif;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        height: 100vh;
                        overflow: hidden;
                    }
                    #auth-panel {
                        background: #0c0c14;
                        padding: 32px;
                        border-radius: 12px;
                        border: 1px solid #1a1a24;
                        text-align: center;
                        box-shadow: 0 4px 20px rgba(0,0,0,0.5);
                    }
                    input {
                        background: #050508;
                        border: 1px solid #1a1a24;
                        color: #f8fafc;
                        padding: 10px 16px;
                        font-size: 18px;
                        border-radius: 6px;
                        text-align: center;
                        margin: 16px 0;
                        outline: none;
                    }
                    input:focus {
                        border-color: #a855f7;
                    }
                    button {
                        background: #a855f7;
                        color: #fff;
                        border: none;
                        padding: 10px 24px;
                        font-size: 16px;
                        border-radius: 6px;
                        cursor: pointer;
                        font-weight: bold;
                        transition: opacity 0.2s;
                    }
                    button:hover {
                        opacity: 0.9;
                    }
                    #screen-container {
                        display: none;
                        position: relative;
                        width: 1280px;
                        height: 720px;
                        border: 1px solid #1a1a24;
                        box-shadow: 0 4px 20px rgba(0,0,0,0.5);
                    }
                    #screen {
                        width: 100%;
                        height: 100%;
                        user-select: none;
                        display: block;
                    }
                </style>
            </head>
            <body>
                <div id="auth-panel">
                    <h2 style="margin-top:0;">Anodyne Remote Access</h2>
                    <p style="color: #94a3b8; font-size: 14px;">Enter 6-digit access PIN displayed on the device status bar:</p>
                    <input type="text" id="pin-input" placeholder="000000" maxlength="6"><br>
                    <button onclick="authenticate()">Connect</button>
                </div>
                <div id="screen-container">
                    <img id="screen" draggable="false">
                </div>
                <script>
                    let pin = "";
                    let screenImg = document.getElementById("screen");
                    let container = document.getElementById("screen-container");
                    let auth = document.getElementById("auth-panel");

                    function authenticate() {
                        pin = document.getElementById("pin-input").value;
                        if (pin.length !== 6) {
                            alert("Please enter a valid 6-digit PIN");
                            return;
                        }
                        auth.style.display = "none";
                        container.style.display = "block";
                        startStream();
                        setupInput();
                    }

                    function startStream() {
                        function loadNext() {
                            screenImg.src = "/api/screenshot?pin=" + pin + "&t=" + Date.now();
                        }
                        screenImg.onload = () => {
                            setTimeout(loadNext, 100); // 10 FPS streaming
                        };
                        screenImg.onerror = () => {
                            setTimeout(loadNext, 1000);
                        };
                        loadNext();
                    }

                    function setupInput() {
                        container.addEventListener("mousemove", (e) => {
                            let rect = container.getBoundingClientRect();
                            let x = (e.clientX - rect.left) / rect.width;
                            let y = (e.clientY - rect.top) / rect.height;
                            sendInput("move", x, y);
                        });

                        container.addEventListener("mousedown", (e) => {
                            let rect = container.getBoundingClientRect();
                            let x = (e.clientX - rect.left) / rect.width;
                            let y = (e.clientY - rect.top) / rect.height;
                            let type = e.button === 2 ? "right_click" : "left_click";
                            sendInput(type, x, y);
                        });

                        container.addEventListener("contextmenu", e => e.preventDefault());
                    }

                    function sendInput(type, x, y) {
                        fetch("/api/input?pin=" + pin + "&type=" + type + "&x=" + x + "&y=" + y)
                            .catch(err => console.error(err));
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
        
        sendResponse(socket, "text/html", html.toByteArray())
    }

    private fun serveScreenshot(socket: Socket, params: Map<String, String>) {
        val clientPin = params["pin"] ?: ""
        if (clientPin != getPin()) {
            sendError(socket, 401, "Unauthorized")
            return
        }

        val webView = getActiveWebView()
        if (webView == null) {
            sendError(socket, 503, "Service Unavailable")
            return
        }

        onConnectionActive(true)

        val bytesStream = ByteArrayOutputStream()
        val doneSignal = java.util.concurrent.CountDownLatch(1)

        mainHandler.post {
            try {
                val w = webView.width.coerceAtLeast(1)
                val h = webView.height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                webView.draw(canvas)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bytesStream)
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error taking WebView screenshot", e)
            } finally {
                doneSignal.countDown()
            }
        }

        doneSignal.await()

        sendResponse(socket, "image/jpeg", bytesStream.toByteArray())
    }

    private fun handleInput(socket: Socket, params: Map<String, String>) {
        val clientPin = params["pin"] ?: ""
        if (clientPin != getPin()) {
            sendError(socket, 401, "Unauthorized")
            return
        }

        val type = params["type"] ?: ""
        val x = params["x"]?.toFloatOrNull() ?: 0f
        val y = params["y"]?.toFloatOrNull() ?: 0f

        handleRemoteInput(type, x, y)
        onConnectionActive(true)

        sendResponse(socket, "text/plain", "OK".toByteArray())
    }

    private fun sendResponse(socket: Socket, contentType: String, data: ByteArray) {
        try {
            val os = socket.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${data.size}\r\n" +
                    "Connection: close\r\n\r\n"
            os.write(header.toByteArray())
            os.write(data)
            os.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP response", e)
        }
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        try {
            val os = socket.getOutputStream()
            val data = message.toByteArray()
            val header = "HTTP/1.1 $code $message\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${data.size}\r\n" +
                    "Connection: close\r\n\r\n"
            os.write(header.toByteArray())
            os.write(data)
            os.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP error response", e)
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (query.isEmpty()) return result
        for (param in query.split("&")) {
            val pair = param.split("=")
            if (pair.size > 1) {
                result[pair[0]] = pair[1]
            }
        }
        return result
    }

    companion object {
        private const val TAG = "DesktopRemoteServer"
    }
}
