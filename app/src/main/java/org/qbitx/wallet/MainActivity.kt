package org.qbitx.wallet

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import org.qbitx.wallet.ui.*
import org.qbitx.wallet.ui.theme.QBitXWalletTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QBitXWalletTheme {
                val viewModel: WalletViewModel = viewModel()
                val state by viewModel.uiState.collectAsState()
                var currentScreen by remember { mutableStateOf("home") }

                if (state.isLocked) {
                    LockScreen(
                        onPinVerify = { pin -> viewModel.verifyPin(pin) },
                        onUnlocked = { viewModel.unlock() }
                    )
                } else {
                    BackHandler(enabled = currentScreen != "home") {
                        currentScreen = "home"
                    }
                    when (currentScreen) {
                        "home" -> WalletScreen(
                            state = state,
                            onCreateWallet = { viewModel.createWallet() },
                            onImportWallet = { backup -> viewModel.importWallet(backup) },
                            onNavigateToSend = { currentScreen = "send" },
                            onNavigateToReceive = { currentScreen = "receive" },
                            onNavigateToSettings = { currentScreen = "settings" },
                            onNavigateToHistory = { currentScreen = "history" },
                            onRefresh = { viewModel.refreshBalance() },
                            onSwitchWallet = { id -> viewModel.switchWallet(id) },
                            onAddWallet = { name -> viewModel.createWallet(name) }
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
                        "history" -> HistoryScreen(
                            state = state,
                            onBack = { currentScreen = "home" }
                        )
                        "settings" -> SettingsScreen(
                            state = state,
                            onConnect = { url -> viewModel.connectToNode(url) },
                            onExportBackup = { viewModel.exportBackup() },
                            onDeleteWallet = {
                                viewModel.deleteWallet()
                                currentScreen = "home"
                            },
                            onBack = { currentScreen = "home" },
                            onSetPin = { pin -> viewModel.setPin(pin) },
                            onRemovePin = { viewModel.removePin() },
                            onRenameWallet = { name -> viewModel.renameWallet(name) },
                            onDeleteActiveWallet = {
                                viewModel.deleteActiveWallet()
                                if (!viewModel.uiState.value.hasWallet) currentScreen = "home"
                            },
                            onSwitchWallet = { id -> viewModel.switchWallet(id) },
                            onAddWallet = { name -> viewModel.createWallet(name) }
                        )
                    }
                }
            }
        }
    }
}
