package com.anodyne.desktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class DesktopRemoteServer(
    private val port: Int,
    private val getPin: () -> String,
    private val getActiveWebView: () -> WebView?,
    private val handleRemoteInput: (String, Float, Float) -> Unit
) {
    private var server: HttpServer? = null
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        try {
            server = HttpServer.create(InetSocketAddress(port), 0).apply {
                createContext("/", DashboardHandler())
                createContext("/api/screenshot", ScreenshotHandler())
                createContext("/api/input", InputHandler())
                setExecutor(executor)
                start()
            }
            Log.i(TAG, "Remote server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start remote server", e)
        }
    }

    fun stop() {
        try {
            server?.stop(0)
            executor.shutdownNow()
            Log.i(TAG, "Remote server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop remote server", e)
        }
    }

    private inner class DashboardHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val response = """
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
            
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { os ->
                os.write(bytes)
            }
        }
    }

    private inner class ScreenshotHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val query = exchange.requestURI.query ?: ""
            val params = parseQuery(query)
            val clientPin = params["pin"] ?: ""

            if (clientPin != getPin()) {
                exchange.sendResponseHeaders(401, 0)
                exchange.close()
                return
            }

            val webView = getActiveWebView()
            if (webView == null) {
                exchange.sendResponseHeaders(503, 0)
                exchange.close()
                return
            }

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

            val bytes = bytesStream.toByteArray()
            exchange.responseHeaders.set("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { os ->
                os.write(bytes)
            }
        }
    }

    private inner class InputHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val query = exchange.requestURI.query ?: ""
            val params = parseQuery(query)
            val clientPin = params["pin"] ?: ""

            if (clientPin != getPin()) {
                exchange.sendResponseHeaders(401, 0)
                exchange.close()
                return
            }

            val type = params["type"] ?: ""
            val x = params["x"]?.toFloatOrNull() ?: 0f
            val y = params["y"]?.toFloatOrNull() ?: 0f

            handleRemoteInput(type, x, y)

            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
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
