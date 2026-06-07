package com.example.expensetracker

import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.example.expensetracker.auth.GoogleAuthManager
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expensetracker.local.ExpenseDatabase
import com.example.expensetracker.local.TransactionEntity
import com.example.expensetracker.repository.LocalRepository
import com.example.expensetracker.ui.dashboard.DashboardScreen
import com.example.expensetracker.ui.dashboard.TransactionDialog
import com.example.expensetracker.ui.theme.ExpenseTrackerTheme
import com.example.expensetracker.viewmodel.TransactionViewModel
import java.util.UUID

class MainActivity : ComponentActivity() {
    
    private lateinit var googleAuthManager: GoogleAuthManager
    private lateinit var expenseDatabase: ExpenseDatabase
    private lateinit var localRepository: LocalRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ENABLE WEBVIEW DEVTOOLS
        WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        googleAuthManager = GoogleAuthManager(this)
        expenseDatabase = ExpenseDatabase.getInstance(this)
        localRepository = LocalRepository(expenseDatabase.transactionDao())

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
            val transactionViewModel: TransactionViewModel = viewModel(
                factory = TransactionViewModel.Factory(localRepository)
            )

            ExpenseTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val transactions by transactionViewModel.allTransactions.collectAsState()
                    val totalBalance by transactionViewModel.totalBalance.collectAsState()
                    val totalIncome by transactionViewModel.totalIncome.collectAsState()
                    val totalExpenses by transactionViewModel.totalExpenses.collectAsState()

                    var showAddTransactionDialog by remember { mutableStateOf(false) }

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

                    DashboardScreen(
                        transactions = transactions.filter { !it.deleted },
                        totalBalance = totalBalance,
                        totalIncome = totalIncome,
                        totalExpenses = totalExpenses,
                        onAddClick = { showAddTransactionDialog = true },
                        onSignInClick = {
                            val signInIntent = googleAuthManager.getSignInIntent()
                            googleSignInLauncher.launch(signInIntent)
                        },
                        onSignOutClick = {
                            googleAuthManager.signOut()
                            Toast.makeText(this@MainActivity, "Signed out", Toast.LENGTH_SHORT).show()
                        }
                    )

                    if (showAddTransactionDialog) {
                        TransactionDialog(
                            onDismissRequest = { showAddTransactionDialog = false },
                            onSave = { type, amount, category, dateMillis, note ->
                                val newTransaction = TransactionEntity(
                                    id = UUID.randomUUID().toString(),
                                    amount = amount,
                                    type = type,
                                    category = category,
                                    note = note,
                                    createdAt = dateMillis,
                                    updatedAt = System.currentTimeMillis(),
                                    deleted = false,
                                    syncPending = true
                                )
                                transactionViewModel.insertTransaction(newTransaction)
                            }
                        )
                    }
                }
            }
        }
    }
}
