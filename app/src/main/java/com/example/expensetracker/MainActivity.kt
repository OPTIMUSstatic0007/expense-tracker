package com.example.expensetracker

import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.example.expensetracker.auth.GoogleAuthManager
import com.example.expensetracker.auth.AuthState
import com.example.expensetracker.backup.BackupManager
import com.example.expensetracker.backup.RestoreManager
import com.example.expensetracker.cloud.CloudFirestoreRepository
import com.example.expensetracker.cloud.ConnectivityMonitor
import com.example.expensetracker.cloud.SyncManager
import com.example.expensetracker.ui.screens.LoginScreen
import com.example.expensetracker.ui.screens.SettingsScreen
import com.example.expensetracker.ui.navigation.Screen
import com.example.expensetracker.ui.theme.ThemeManager
import com.example.expensetracker.ui.theme.ThemeMode
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.expensetracker.ui.theme.ExpenseTrackerTheme
import com.example.expensetracker.local.ExpenseDatabase
import com.example.expensetracker.repository.LocalRepository
import com.example.expensetracker.bridge.AndroidBridge
import com.example.expensetracker.backup.BackupLifecycleManager
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    // URL for the bundled offline WebView app
    private val appUrl = "file:///android_asset/index.html"
    private lateinit var googleAuthManager: GoogleAuthManager
    private lateinit var themeManager: ThemeManager
    private lateinit var cloudFirestoreRepository: CloudFirestoreRepository
    private lateinit var connectivityMonitor: ConnectivityMonitor
    private lateinit var syncManager: SyncManager

    // Navigation state — which screen to show when authenticated
    private val currentScreen = mutableStateOf(Screen.Dashboard)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ENABLE WEBVIEW DEVTOOLS
        WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        googleAuthManager = GoogleAuthManager(this)
        themeManager = ThemeManager(this)
        cloudFirestoreRepository = CloudFirestoreRepository()
        connectivityMonitor = ConnectivityMonitor(this)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"
        syncManager = SyncManager(cloudFirestoreRepository, connectivityMonitor, deviceId)

        val googleSignInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            googleAuthManager.handleSignInResult(
                result.data,

                onSuccess = { email ->

                    Toast.makeText(
                        this,
                        "Signed in: $email",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onError = { error ->

                    Toast.makeText(
                        this,
                        error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
        setContent {
            // Collect theme state from the single source of truth
            val themeMode by themeManager.themeMode.collectAsState()
            val isSystemDark = isSystemInDarkTheme()
            val isDark = themeManager.isDark(isSystemDark)

            ExpenseTrackerTheme(darkTheme = isDark) {
                // Collect auth state reactively
                val authState by googleAuthManager.authState.collectAsState()

                // Sign-in loading & error state for LoginScreen
                var isSigningIn by remember { mutableStateOf(false) }
                var signInError by remember { mutableStateOf<String?>(null) }

                // Reset signing-in state when auth state changes to Authenticated
                LaunchedEffect(authState) {
                    when (val state = authState) {
                        is AuthState.Authenticated -> {
                            isSigningIn = false
                            signInError = null
                            currentScreen.value = Screen.Dashboard
                            syncManager.onUserAuthenticated(state.user)
                        }

                        is AuthState.Unauthenticated -> {
                            syncManager.onUserSignedOut()
                        }

                        is AuthState.Loading -> Unit
                    }
                }

                when (val state = authState) {
                    is AuthState.Loading -> {
                        // Full-screen loading while checking session
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    is AuthState.Unauthenticated -> {
                        LoginScreen(
                            isLoading = isSigningIn,
                            errorMessage = signInError,
                            onSignInClick = {
                                isSigningIn = true
                                signInError = null
                                val signInIntent = googleAuthManager.getSignInIntent()
                                googleSignInLauncher.launch(signInIntent)
                            }
                        )
                    }

                    is AuthState.Authenticated -> {
                        val screen = currentScreen.value

                        when (screen) {
                            Screen.Dashboard -> {
                                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                                    // Request Notification Permission for API 33+ to show Download Progress
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val permissionLauncher = rememberLauncherForActivityResult(
                                            ActivityResultContracts.RequestPermission()
                                        ) { isGranted ->
                                            Log.d("ExpenseTracker", "Notification permission granted: $isGranted")
                                        }
                                        LaunchedEffect(Unit) {
                                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }

                                    ExpenseTrackerWebView(
                                        url = appUrl,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(innerPadding),
                                        themeManager = themeManager,
                                        isDark = isDark,
                                        syncManager = syncManager,
                                        onOpenSettings = {
                                            // AndroidBridge calls from a background thread,
                                            // must switch to main thread for Compose state update
                                            Handler(Looper.getMainLooper()).post {
                                                currentScreen.value = Screen.Settings
                                            }
                                        }
                                    )
                                }
                            }

                            Screen.Settings -> {
                                SettingsScreen(
                                    user = state.user,
                                    themeMode = themeMode,
                                    onThemeChange = { mode -> themeManager.setTheme(mode) },
                                    onSignOut = {
                                        googleAuthManager.signOut()
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Signed out",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onBack = {
                                        currentScreen.value = Screen.Dashboard
                                    }
                                )
                            }

                            Screen.Login -> {
                                // Should not happen when authenticated,
                                // but handle gracefully
                                currentScreen.value = Screen.Dashboard
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        syncManager.shutdown()
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ExpenseTrackerWebView(
    url: String,
    modifier: Modifier = Modifier,
    themeManager: ThemeManager? = null,
    isDark: Boolean = false,
    syncManager: SyncManager? = null,
    onOpenSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val repository = remember(context, syncManager) {
        LocalRepository(context, syncManager)
    }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var uploadMessage by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // Register receiver to log download completion.
    // On API 33+ the flag is required; on older APIs the flag overload is unavailable.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) {
                    Log.d("ExpenseTracker", "File saved to Downloads (ID: $id)")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val results = if (data != null) {
                val clipData = data.clipData
                if (clipData != null) {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else null
            uploadMessage?.onReceiveValue(results)
        } else {
            uploadMessage?.onReceiveValue(null)
        }
        uploadMessage = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    // Required for local file:// asset cross-origin access within the bundled app.
                    // These are deprecated in API 31 but remain necessary for file:// WebView assets.
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true

                    val database = ExpenseDatabase.getInstance(context)
                    syncManager?.attachLocalRepository(repository)
                    val backupManager = BackupManager(context, database)
                    val lifecycleManager = BackupLifecycleManager(context, backupManager)
                    val restoreManager = RestoreManager(context, database, backupManager, lifecycleManager)
                    addJavascriptInterface(
                        AndroidBridge(repository, backupManager, restoreManager, lifecycleManager, context, themeManager ?: ThemeManager(context), onOpenSettings),
                        "AndroidBridge"
                    )

                    // Change 2: Launch startup maintenance asynchronously after Room init.
                    // Never blocks UI rendering or first interaction.
                    thread(start = true, isDaemon = true, name = "BackupLifecycleMaintenance") {
                        try {
                            lifecycleManager.runStartupMaintenance()
                        } catch (e: Exception) {
                            android.util.Log.e("BackupLifecycle", "Startup maintenance failed", e)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                            errorMessage = null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            // Push the current theme into the WebView after page load
                            val currentTheme = if (themeManager?.isDark(
                                    (context.resources.configuration.uiMode
                                            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                            android.content.res.Configuration.UI_MODE_NIGHT_YES
                                ) == true) "dark" else "light"
                            view?.evaluateJavascript(
                                "if(typeof applyTheme==='function'){applyTheme('$currentTheme');}",
                                null
                            )
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                isLoading = false
                                errorMessage = "Failed to connect to backend: ${error?.description ?: "Unknown error"}"
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            uploadMessage?.onReceiveValue(null)
                            uploadMessage = filePathCallback
                            val intent = fileChooserParams?.createIntent()
                            if (intent != null) {
                                try {
                                    fileChooserLauncher.launch(intent)
                                } catch (_: Exception) {
                                    uploadMessage = null
                                    return false
                                }
                            } else {
                                uploadMessage = null
                                return false
                            }
                            return true
                        }
                    }

                    setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                        Log.d("ExpenseTracker", "WebView download requested: $downloadUrl")
                        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                            setMimeType(mimetype)
                            addRequestHeader("User-Agent", userAgent)
                            setDescription("Downloading file...")
                            val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                            setTitle(fileName)
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                        }
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        try {
                            dm.enqueue(request)
                            Log.d("ExpenseTracker", "DownloadManager enqueue success for $downloadUrl")
                        } catch (e: Exception) {
                            Log.e("ExpenseTracker", "Download failed: ${e.message}")
                        }
                    }

                    webViewInstance = this
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Push live theme updates to the WebView whenever isDark changes
        // (e.g. user switches theme from SettingsScreen or drawer)
        LaunchedEffect(isDark) {
            val themeName = if (isDark) "dark" else "light"
            webViewInstance?.evaluateJavascript(
                "if(typeof applyTheme==='function'){applyTheme('$themeName');}",
                null
            )
        }

        LaunchedEffect(repository, webViewInstance) {
            repository.getAllTransactions().collectLatest {
                webViewInstance?.evaluateJavascript(
                    "if(typeof loadTransactions==='function'){currentPage=1;loadTransactions(false);}",
                    null
                )
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }

        errorMessage?.let { msg ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = msg)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        errorMessage = null
                        webViewInstance?.reload()
                    }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}
