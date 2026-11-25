package com.example

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.example.ui.theme.MyApplicationTheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val pendingNotificationTarget = mutableStateOf<Pair<String, String>?>(null)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        val results: Array<Uri>? = if (result.resultCode == RESULT_OK && result.data != null) {
            val clipData = result.data?.clipData
            if (clipData != null) {
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else {
                result.data?.data?.let { arrayOf(it) }
            }
        } else {
            null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Create notification channels for incoming chat messages & calls
        NotificationHelper.createNotificationChannels(applicationContext)

        // Ensure silent background notification sync runs if user already has a saved session
        if (NotificationStore.hasSession(applicationContext)) {
            SilentSseManager.ensureRunning(applicationContext)
            NotificationSyncJobService.scheduleAll(applicationContext)
            NotificationAlarmReceiver.scheduleNext(applicationContext)
        }

        // Pre-fetch FCM token
        MyFirebaseMessagingService.fetchAndLogToken(applicationContext)

        extractNotificationIntent(intent)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .imePadding(),
                    color = Color(0xFF0A0E27)
                ) {
                    ChatWebViewScreen(
                        activity = this,
                        pendingNotificationTarget = pendingNotificationTarget.value,
                        onNotificationTargetConsumed = { pendingNotificationTarget.value = null },
                        onOpenFileChooser = { callback, intent ->
                            filePathCallback = callback
                            filePickerLauncher.launch(intent)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        NotificationStore.isAppForeground = true
        if (NotificationStore.hasSession(applicationContext)) {
            SilentSseManager.ensureRunning(applicationContext)
            NotificationSyncJobService.scheduleAll(applicationContext)
            NotificationAlarmReceiver.scheduleNext(applicationContext)
        }
    }

    override fun onPause() {
        super.onPause()
        NotificationStore.isAppForeground = false
        if (NotificationStore.hasSession(applicationContext)) {
            NotificationSyncJobService.scheduleFastCheck(applicationContext, forceReschedule = true)
            NotificationAlarmReceiver.scheduleNext(applicationContext, delayMs = 15_000L)
        }
    }

    override fun onStop() {
        super.onStop()
        NotificationStore.isAppForeground = false
        if (NotificationStore.hasSession(applicationContext)) {
            NotificationSyncJobService.scheduleFastCheck(applicationContext, forceReschedule = true)
            NotificationAlarmReceiver.scheduleNext(applicationContext, delayMs = 15_000L)
        }
    }

    private fun extractNotificationIntent(intent: Intent?) {
        // Launched by tapping a push notification -> web UI must skip the intro animation.
        val fromNotification = intent?.hasExtra("notification_type") == true
        NotificationStore.launchedFromNotification = fromNotification
        val senderUid = intent?.getStringExtra("sender_uid") ?: ""
        val senderName = intent?.getStringExtra("sender_name") ?: ""
        if (senderUid.isNotBlank()) {
            pendingNotificationTarget.value = senderUid to senderName
        }
    }
}

class AndroidBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?
) {
    /**
     * Saves a base64 data-URL (or raw base64) to the phone's public Downloads/ChatOG folder.
     * Returns true on success. Works without storage permission on Android 10+.
     */
    @JavascriptInterface
    fun saveFileToDownloads(dataUrl: String, fileName: String, mimeType: String): Boolean {
        return try {
            val comma = dataUrl.indexOf(',')
            val base64 = if (dataUrl.startsWith("data:") && comma >= 0) dataUrl.substring(comma + 1) else dataUrl
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file_${System.currentTimeMillis()}" }
            val mime = mimeType.ifBlank { "application/octet-stream" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/ChatOG")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "ChatOG"
                )
                val target = if (dir.exists() || dir.mkdirs()) dir else context.getExternalFilesDir(null) ?: return false
                java.io.File(target, safeName).writeBytes(bytes)
            }
            true
        } catch (e: Exception) {
            Log.e("AndroidBridge", "saveFileToDownloads failed: ${e.message}")
            false
        }
    }

    /** True when the app was opened by tapping a notification (JS skips the splash animation). */
    @JavascriptInterface
    fun isNotificationLaunch(): Boolean = NotificationStore.launchedFromNotification

    @JavascriptInterface
    fun getFcmToken(): String {
        val token = MyFirebaseMessagingService.getSavedToken(context)
        Log.d("AndroidBridge", "getFcmToken called from JS, returning length=${token.length}")
        return token
    }

    @JavascriptInterface
    fun saveAuthSession(
        uid: String,
        idToken: String,
        refreshToken: String,
        apiKey: String,
        databaseUrl: String,
        loginTimestampMs: Long
    ) {
        Log.d("AndroidBridge", "saveAuthSession uid=$uid")
        NotificationStore.saveSession(
            context = context,
            uid = uid,
            idToken = idToken,
            refreshToken = refreshToken,
            apiKey = apiKey,
            databaseUrl = databaseUrl,
            loginTimestampMs = loginTimestampMs
        )
        SilentSseManager.ensureRunning(context)
        NotificationSyncJobService.scheduleAll(context)
        NotificationAlarmReceiver.scheduleNext(context)
    }

    @JavascriptInterface
    fun clearAuthSession() {
        Log.d("AndroidBridge", "clearAuthSession")
        NotificationStore.clearSession(context)
        SilentSseManager.stop()
        NotificationSyncJobService.cancel(context)
        NotificationAlarmReceiver.cancel(context)
        BadgeHelper.clearBadge(context)
    }

    @JavascriptInterface
    fun syncUnreadBadgeCount(totalUnread: Int) {
        val safeCount = totalUnread.coerceAtLeast(0)
        NotificationStore.setUnreadBadgeCount(context, safeCount)
        BadgeHelper.applyLauncherBadge(context, safeCount)
    }

    @JavascriptInterface
    fun copyToClipboard(label: String, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (e: Exception) {
            Log.w("AndroidBridge", "Clipboard error: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun setActiveChat(chatUid: String) {
        NotificationStore.activeChatUid = chatUid
    }

    @JavascriptInterface
    fun markNotifSeen(notifId: String, timestampMs: Long) {
        NotificationStore.tryMarkNotifSeen(context, notifId)
        NotificationStore.updateLastNotifTimestamp(context, timestampMs)
    }

    @JavascriptInterface
    fun showNotificationWithId(
        notifId: String,
        title: String,
        body: String,
        type: String,
        senderName: String,
        senderDp: String,
        chatId: String,
        senderUid: String
    ) {
        Log.d("AndroidBridge", "showNotificationWithId: $notifId - $title ($type)")
        NotificationHelper.showNotification(
            context = context,
            title = title,
            body = body,
            type = type,
            senderName = senderName,
            senderDp = senderDp,
            chatId = chatId,
            senderUid = senderUid,
            notifId = notifId
        )
    }

    @JavascriptInterface
    fun showNotification(
        title: String,
        body: String,
        type: String,
        senderName: String,
        senderDp: String,
        chatId: String,
        senderUid: String
    ) {
        Log.d("AndroidBridge", "showNotification called: $title - $body ($type)")
        NotificationHelper.showNotification(
            context = context,
            title = title,
            body = body,
            type = type,
            senderName = senderName,
            senderDp = senderDp,
            chatId = chatId,
            senderUid = senderUid
        )
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs.coerceAtLeast(10), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs.coerceAtLeast(10))
            }
        } catch (e: Exception) {
            Log.w("AndroidBridge", "Vibrate error: ${e.message}")
        }
    }

    @JavascriptInterface
    fun logInfo(tag: String, msg: String) {
        Log.i("JS_$tag", msg)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatWebViewScreen(
    activity: ComponentActivity,
    pendingNotificationTarget: Pair<String, String>?,
    onNotificationTargetConsumed: () -> Unit,
    onOpenFileChooser: (ValueCallback<Array<Uri>>, Intent) -> Unit
) {
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var isPageLoaded by remember { mutableStateOf(false) }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("ChatWebViewScreen", "Media permissions result: $permissions")
        pendingPermissionRequest?.let { req ->
            req.grant(req.resources)
            pendingPermissionRequest = null
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("ChatWebViewScreen", "Notification permission granted: $granted")
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(pendingNotificationTarget, isPageLoaded) {
        val target = pendingNotificationTarget
        val wv = webViewHolder[0]
        if (target != null && isPageLoaded && wv != null) {
            val uidJson = JSONObject.quote(target.first)
            val nameJson = JSONObject.quote(target.second)
            wv.evaluateJavascript(
                "if (typeof window.handleNotificationTap === 'function') { window.handleNotificationTap($uidJson, $nameJson); }",
                null
            )
            onNotificationTargetConsumed()
        }
    }

    // Handle phone's hardware/system back button hierarchically across JS menus/modals & double-back exit at main menu
    BackHandler(enabled = true) {
        val wv = webViewHolder[0]
        if (wv != null) {
            wv.evaluateJavascript(
                "(function(){ return typeof window.handleAndroidBackButton === 'function' ? window.handleAndroidBackButton() : 'NO_JS'; })()"
            ) { rawResult ->
                val result = rawResult?.trim('"') ?: "NO_JS"
                if (result == "HANDLED" || result == "IN_CALL") {
                    // Closed active modal/drawer/chat or returned to previous menu tab
                } else if (wv.canGoBack()) {
                    wv.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000L) {
                        activity.finish()
                    } else {
                        lastBackPressTime = now
                        Toast.makeText(
                            activity,
                            "Çıxış etmək üçün yenidən geri düyməsinə basın",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000L) {
                activity.finish()
            } else {
                lastBackPressTime = now
                Toast.makeText(
                    activity,
                    "Çıxış etmək üçün yenidən geri düyməsinə basın",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .testTag("chat_webview"),
        factory = { ctx ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()

            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(AndroidColor.parseColor("#0A0E27"))
                webViewHolder[0] = this
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                overScrollMode = android.view.View.OVER_SCROLL_NEVER

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportZoom(false)
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                addJavascriptInterface(
                    AndroidBridge(ctx) { webViewHolder[0] },
                    "AndroidBridge"
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url ?: return null
                        val response = assetLoader.shouldInterceptRequest(url)
                        if (response != null) {
                            val path = url.path ?: ""
                            if (path.endsWith(".js") || path.endsWith(".mjs")) {
                                response.mimeType = "application/javascript"
                            } else if (path.endsWith(".html")) {
                                response.mimeType = "text/html"
                            }
                        }
                        return response
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        Log.e("ChatWebViewScreen", "WebView error: ${error?.description} for ${request?.url}")
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        Log.e("ChatWebViewScreen", "WebView HTTP error: ${errorResponse?.statusCode} for ${request?.url}")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("ChatWebViewScreen", "Page loaded: $url")
                        isPageLoaded = true
                        // Send FCM token to WebView if ready
                        MyFirebaseMessagingService.fetchAndLogToken(ctx) { token ->
                            post {
                                evaluateJavascript(
                                    "if (typeof window.onNativeFcmTokenReady === 'function') { window.onNativeFcmTokenReady('${token}'); }",
                                    null
                                )
                            }
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Log.d("WebViewConsole", "[${it.messageLevel()}] ${it.message()} -- line ${it.lineNumber()} of ${it.sourceId()}")
                        }
                        return true
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.let { req ->
                            val needed = mutableListOf<String>()
                            if (req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                            ) {
                                needed.add(android.Manifest.permission.RECORD_AUDIO)
                            }
                            if (req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                            ) {
                                needed.add(android.Manifest.permission.CAMERA)
                            }
                            if (needed.isEmpty()) {
                                req.grant(req.resources)
                            } else {
                                pendingPermissionRequest = req
                                mediaPermissionLauncher.launch(needed.toTypedArray())
                            }
                        }
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        filePathCallback?.let { callback ->
                            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "*/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                            }
                            onOpenFileChooser(callback, intent)
                            return true
                        }
                        return false
                    }

                    override fun onJsAlert(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        result: android.webkit.JsResult?
                    ): Boolean {
                        AlertDialog.Builder(ctx)
                            .setTitle("ORUJOV Chat")
                            .setMessage(message ?: "")
                            .setPositiveButton("Oldu") { dialog, _ ->
                                dialog.dismiss()
                                result?.confirm()
                            }
                            .setOnCancelListener {
                                result?.cancel()
                            }
                            .create()
                            .show()
                        return true
                    }

                    override fun onJsConfirm(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        result: android.webkit.JsResult?
                    ): Boolean {
                        AlertDialog.Builder(ctx)
                            .setTitle("ORUJOV Chat")
                            .setMessage(message ?: "")
                            .setPositiveButton("Bəli") { dialog, _ ->
                                dialog.dismiss()
                                result?.confirm()
                            }
                            .setNegativeButton("Xeyr") { dialog, _ ->
                                dialog.dismiss()
                                result?.cancel()
                            }
                            .setOnCancelListener {
                                result?.cancel()
                            }
                            .create()
                            .show()
                        return true
                    }
                }

                loadUrl("https://appassets.androidplatform.net/assets/index.html")
            }
        },
        update = {
            webViewHolder[0] = it
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.destroy()
            webViewHolder[0] = null
        }
    }
}
