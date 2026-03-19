package org.qbitx.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.qbitx.wallet.ui.*
import org.qbitx.wallet.ui.theme.QBitXWalletTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QBitXWalletTheme {
                val viewModel: WalletViewModel = viewModel()
                val state by viewModel.uiState.collectAsState()
                var currentScreen by remember { mutableStateOf("home") }

                when (currentScreen) {
                    "home" -> WalletScreen(
                        state = state,
                        onCreateWallet = { viewModel.createWallet() },
                        onNavigateToSend = { currentScreen = "send" },
                        onNavigateToReceive = { currentScreen = "receive" },
                        onNavigateToSettings = { currentScreen = "settings" },
                        onRefresh = { viewModel.refreshBalance() }
                    )
                    "send" -> SendScreen(
                        state = state,
                        onSend = { addr, amt, fee -> viewModel.sendQBX(addr, amt, fee) },
                        onBack = { currentScreen = "home" }
                    )
                    "receive" -> ReceiveScreen(
                        address = state.address,
                        onBack = { currentScreen = "home" }
                    )
                    "settings" -> SettingsScreen(
                        state = state,
                        onConnect = { host, port, user, pass, wallet ->
                            viewModel.connectToNode(host, port, user, pass, wallet)
                        },
                        onBack = { currentScreen = "home" }
                    )
                }
            }
        }
    }
}
