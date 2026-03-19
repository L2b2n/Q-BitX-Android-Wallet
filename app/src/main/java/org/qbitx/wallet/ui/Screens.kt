package org.qbitx.wallet.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import org.qbitx.wallet.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.qbitx.wallet.data.TxRecord
import org.qbitx.wallet.data.WalletInfo
import org.qbitx.wallet.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

// ==================== CARD STYLE ====================

val BalanceGradient = Brush.linearGradient(
    colors = listOf(QBXPurple, QBXBlueDark),
    start = Offset(0f, 0f),
    end = Offset(800f, 400f)
)

private fun Modifier.solidCard(): Modifier = this
    .clip(RoundedCornerShape(16.dp))
    .background(QBXSurface)

// ==================== LOCK SCREEN ====================

@Composable
fun LockScreen(onPinVerify: (String) -> Boolean, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val canBiometric = remember {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun triggerBiometric() {
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.lock_biometric_title))
                .setSubtitle(context.getString(R.string.lock_biometric_subtitle))
                .setNegativeButtonText(context.getString(R.string.lock_biometric_negative))
                .build()
        )
    }

    LaunchedEffect(Unit) { if (canBiometric) triggerBiometric() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QBXBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, null, tint = QBXPurple, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.lock_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = QBXOnSurface)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.lock_enter_pin), fontSize = 14.sp, color = QBXOnSurfaceDim)
        Spacer(Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (i < pin.length) QBXPurple else QBXDivider)
                )
            }
        }

        if (error) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.lock_wrong_pin), color = QBXRed, fontSize = 13.sp)
        }

        Spacer(Modifier.height(32.dp))

        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(if (canBiometric) "bio" else "", "0", "del")
        )
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(0.75f),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(Modifier.size(64.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(if (key == "bio") QBXPurple.copy(alpha = 0.15f) else QBXSurface)
                                .clickable {
                                    when (key) {
                                        "del" -> if (pin.isNotEmpty()) { pin = pin.dropLast(1); error = false }
                                        "bio" -> triggerBiometric()
                                        else -> {
                                            if (pin.length < 4) {
                                                pin += key
                                                if (pin.length == 4) {
                                                    if (onPinVerify(pin)) onUnlocked()
                                                    else { error = true; pin = "" }
                                                }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            when (key) {
                                "bio" -> Icon(Icons.Default.Fingerprint, null, tint = QBXPurple, modifier = Modifier.size(28.dp))
                                "del" -> Icon(Icons.Default.Backspace, null, tint = QBXOnSurfaceDim, modifier = Modifier.size(24.dp))
                                else -> Text(key, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = QBXOnSurface)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ==================== MAIN WALLET SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    state: WalletUiState,
    onCreateWallet: () -> Unit,
    onImportWallet: (String) -> Unit,
    onNavigateToSend: () -> Unit,
    onNavigateToReceive: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchWallet: (Int) -> Unit,
    onAddWallet: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QBXBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))

        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Q-BitX", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = QBXOnSurface)
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Outlined.Settings, "Settings", tint = QBXOnSurfaceDim)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!state.hasWallet) {
            // ===== No wallet — creation screen =====
            Spacer(Modifier.height(60.dp))

            Icon(
                Icons.Default.Shield,
                null,
                tint = QBXPurple,
                modifier = Modifier.size(64.dp)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.welcome_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = QBXOnSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.welcome_subtitle),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                color = QBXOnSurfaceDim,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = onCreateWallet,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !state.isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = QBXPurple)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = QBXOnSurface, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.btn_create_wallet), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Import wallet
            var showImport by remember { mutableStateOf(false) }
            var importKey by remember { mutableStateOf("") }

            OutlinedButton(
                onClick = { showImport = !showImport },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = QBXOnSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, QBXDivider)
            ) {
                Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.btn_import_wallet), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            if (showImport) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = importKey,
                    onValueChange = { importKey = it },
                    placeholder = { Text(stringResource(R.string.import_placeholder), color = QBXOnSurfaceDim.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QBXPurple,
                        unfocusedBorderColor = QBXDivider,
                        focusedContainerColor = QBXSurface,
                        unfocusedContainerColor = QBXSurface,
                        cursorColor = QBXPurple,
                        focusedTextColor = QBXOnSurface,
                        unfocusedTextColor = QBXOnSurface
                    )
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onImportWallet(importKey) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = importKey.contains(":") && !state.isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = QBXPurple)
                ) {
                    Text(stringResource(R.string.btn_import), fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Feature list
            @Composable fun featureList() = listOf(
                Triple(Icons.Default.Shield, stringResource(R.string.feature_dilithium), stringResource(R.string.feature_dilithium_sub)),
                Triple(Icons.Default.Lock, stringResource(R.string.feature_encrypted), stringResource(R.string.feature_encrypted_sub)),
                Triple(Icons.Default.Speed, stringResource(R.string.feature_pow), stringResource(R.string.feature_pow_sub))
            )
            featureList().forEach { (icon, title, subtitle) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .solidCard()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, tint = QBXPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QBXOnSurface)
                        Text(subtitle, fontSize = 12.sp, color = QBXOnSurfaceDim)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        } else {
            // ===== Wallet exists — dashboard =====

            // Wallet selector
            var showWalletMenu by remember { mutableStateOf(false) }
            var showAddDialog by remember { mutableStateOf(false) }
            var newWalletName by remember { mutableStateOf("") }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .solidCard()
                    .clickable { showWalletMenu = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AccountBalanceWallet, null, tint = QBXPurple, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.activeWalletName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
                    Text(if (state.wallets.size != 1) stringResource(R.string.wallet_count_plural, state.wallets.size) else stringResource(R.string.wallet_count, state.wallets.size), fontSize = 12.sp, color = QBXOnSurfaceDim)
                }
                Icon(Icons.Default.UnfoldMore, null, tint = QBXOnSurfaceDim, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(QBXPurple.copy(alpha = 0.15f))
                        .clickable { showAddDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, null, tint = QBXPurple, modifier = Modifier.size(18.dp))
                }

                DropdownMenu(expanded = showWalletMenu, onDismissRequest = { showWalletMenu = false }) {
                    state.wallets.forEach { wallet ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (wallet.address == state.address) {
                                        Icon(Icons.Default.Check, null, tint = QBXPurple, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Column {
                                        Text(wallet.name, fontWeight = FontWeight.Medium)
                                        Text(wallet.address.take(16) + "...", fontSize = 11.sp, color = QBXOnSurfaceDim)
                                    }
                                }
                            },
                            onClick = {
                                onSwitchWallet(wallet.id)
                                showWalletMenu = false
                            }
                        )
                    }
                }
            }

            if (showAddDialog) {
                AlertDialog(
                    onDismissRequest = { showAddDialog = false; newWalletName = "" },
                    containerColor = QBXSurface,
                    titleContentColor = QBXOnSurface,
                    textContentColor = QBXOnSurface,
                    shape = RoundedCornerShape(16.dp),
                    title = { Text(stringResource(R.string.new_wallet_title), fontWeight = FontWeight.SemiBold) },
                    text = {
                        OutlinedTextField(
                            value = newWalletName,
                            onValueChange = { newWalletName = it },
                            placeholder = { Text(stringResource(R.string.new_wallet_name_hint), color = QBXOnSurfaceDim.copy(alpha = 0.5f)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = QBXPurple, unfocusedBorderColor = QBXDivider,
                                focusedContainerColor = QBXSurface, unfocusedContainerColor = QBXSurface,
                                cursorColor = QBXPurple, focusedTextColor = QBXOnSurface, unfocusedTextColor = QBXOnSurface
                            )
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { onAddWallet(newWalletName); showAddDialog = false; newWalletName = "" },
                            colors = ButtonDefaults.buttonColors(containerColor = QBXPurple),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(stringResource(R.string.btn_create)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddDialog = false; newWalletName = "" }) {
                            Text(stringResource(R.string.btn_cancel), color = QBXOnSurfaceDim)
                        }
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Balance card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BalanceGradient)
                    .padding(24.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.balance_label), fontSize = 14.sp, color = QBXOnSurface.copy(alpha = 0.7f))
                        if (state.nodeConnected) {
                            Text(
                                "Block ${state.blockHeight}",
                                fontSize = 12.sp,
                                color = QBXOnSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "%.8f".format(state.balance),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = QBXOnSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "QBX",
                            fontSize = 16.sp,
                            color = QBXOnSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    if (state.unconfirmedBalance != 0.0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.unconfirmed_label, "%.8f".format(state.unconfirmedBalance)),
                            fontSize = 13.sp,
                            color = QBXOnSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Send
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .solidCard()
                        .clickable(enabled = state.nodeConnected) { onNavigateToSend() }
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.ArrowUpward, null, tint = QBXPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.btn_send), fontSize = 13.sp, color = QBXOnSurface)
                }

                // Receive
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .solidCard()
                        .clickable { onNavigateToReceive() }
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.ArrowDownward, null, tint = QBXPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.btn_receive), fontSize = 13.sp, color = QBXOnSurface)
                }

                // Refresh
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .solidCard()
                        .clickable { onRefresh() }
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Refresh, null, tint = QBXPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.btn_refresh), fontSize = 13.sp, color = QBXOnSurface)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Address card
            AddressCard(state.address)

            Spacer(Modifier.height(16.dp))

            // Connection status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .solidCard()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.nodeConnected) QBXGreen else QBXRed)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.nodeConnected) stringResource(R.string.connected) else stringResource(R.string.not_connected),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = QBXOnSurface
                    )
                    if (state.nodeConnected) {
                        Text("${state.chain} · Dilithium3", fontSize = 12.sp, color = QBXOnSurfaceDim)
                    }
                }
                Icon(Icons.Default.Shield, null, tint = QBXGreen.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            }

            // TX History
            if (state.txHistory.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.recent_tx), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
                    TextButton(onClick = onNavigateToHistory) {
                        Text(stringResource(R.string.view_all_tx), fontSize = 12.sp, color = QBXPurple)
                    }
                }
                Spacer(Modifier.height(8.dp))

                val dateFormat = remember { SimpleDateFormat("dd.MM.yy HH:mm", Locale.GERMAN) }
                state.txHistory.take(5).forEach { tx ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .solidCard()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ArrowUpward, null, tint = QBXRed.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "An: ${tx.toAddress.take(12)}...${tx.toAddress.takeLast(6)}",
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = QBXOnSurface
                            )
                            Text(dateFormat.format(Date(tx.timestamp)), fontSize = 11.sp, color = QBXOnSurfaceDim)
                        }
                        Text(
                            "-${"%.4f".format(tx.amount)} QBX",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = QBXRed.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // Error display
        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(QBXRed.copy(alpha = 0.1f))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.ErrorOutline, null, tint = QBXRed, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(error, color = QBXRed.copy(alpha = 0.9f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ==================== ADDRESS CARD ====================

@Composable
fun AddressCard(address: String) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .solidCard()
            .clickable {
                clipboardManager.setText(AnnotatedString(address))
                copied = true
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.QrCode2, null, tint = QBXPurple, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.pq_address), fontSize = 11.sp, color = QBXOnSurfaceDim)
            Spacer(Modifier.height(2.dp))
            Text(
                address,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = QBXOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            null,
            tint = if (copied) QBXGreen else QBXOnSurfaceDim,
            modifier = Modifier.size(18.dp)
        )
    }
}

// ==================== SEND SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    state: WalletUiState,
    onSend: (String, Double, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var toAddress by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var feePolicy by remember { mutableStateOf("normal") }
    var showConfirm by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { toAddress = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QBXBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.send_back), tint = QBXOnSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.send_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            // Amount display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .solidCard()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (amount.isNotEmpty()) amount else "0",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = QBXOnSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("QBX", fontSize = 16.sp, color = QBXOnSurfaceDim,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.send_available, "%.8f".format(state.balance)),
                        fontSize = 12.sp,
                        color = QBXOnSurfaceDim
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // To Address input
            Text(stringResource(R.string.send_recipient), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QBXOnSurfaceDim)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = toAddress,
                onValueChange = { toAddress = it },
                placeholder = { Text(stringResource(R.string.send_address_hint), color = QBXOnSurfaceDim.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QBXPurple,
                    unfocusedBorderColor = QBXDivider,
                    focusedContainerColor = QBXSurface,
                    unfocusedContainerColor = QBXSurface,
                    cursorColor = QBXPurple,
                    focusedTextColor = QBXOnSurface,
                    unfocusedTextColor = QBXOnSurface
                ),
                leadingIcon = { Icon(Icons.Default.Person, null, tint = QBXOnSurfaceDim) },
                trailingIcon = {
                    IconButton(onClick = {
                        val options = ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt(context.getString(R.string.send_qr_prompt))
                            setBeepEnabled(false)
                            setOrientationLocked(true)
                        }
                        scanLauncher.launch(options)
                    }) {
                        Icon(Icons.Default.QrCodeScanner, stringResource(R.string.send_scan_qr), tint = QBXPurple)
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // Amount input
            Text(stringResource(R.string.send_amount), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QBXOnSurfaceDim)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                placeholder = { Text("0.00000000", color = QBXOnSurfaceDim.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QBXPurple,
                    unfocusedBorderColor = QBXDivider,
                    focusedContainerColor = QBXSurface,
                    unfocusedContainerColor = QBXSurface,
                    cursorColor = QBXPurple,
                    focusedTextColor = QBXOnSurface,
                    unfocusedTextColor = QBXOnSurface
                ),
                leadingIcon = { Icon(Icons.Default.Payments, null, tint = QBXOnSurfaceDim) }
            )

            Spacer(Modifier.height(20.dp))

            // Fee policy
            Text(stringResource(R.string.send_fees), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QBXOnSurfaceDim)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    Triple("low", stringResource(R.string.fee_low), "1 sat/vB"),
                    Triple("normal", stringResource(R.string.fee_normal), "5 sat/vB"),
                    Triple("high", stringResource(R.string.fee_high), "15 sat/vB")
                ).forEach { (key, label, sub) ->
                    val selected = feePolicy == key
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) QBXPurple.copy(alpha = 0.15f) else QBXSurface)
                            .border(
                                1.dp,
                                if (selected) QBXPurple else QBXDivider,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { feePolicy = key }
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) QBXPurple else QBXOnSurfaceDim
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(sub, fontSize = 11.sp, color = QBXOnSurfaceDim.copy(alpha = 0.6f))
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // Send button
            Button(
                onClick = { showConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = toAddress.isNotBlank() && amount.toDoubleOrNull() != null && amount.toDouble() > 0 && !state.isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = QBXPurple,
                    disabledContainerColor = QBXPurple.copy(alpha = 0.3f)
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = QBXOnSurface, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.btn_send), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Success
            state.lastTxId?.let { txid ->
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(QBXGreen.copy(alpha = 0.1f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = QBXGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.tx_sent), color = QBXGreen, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("TXID: $txid", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = QBXGreen.copy(alpha = 0.8f), maxLines = 2)
                    }
                }
            }

            // Error
            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(QBXRed.copy(alpha = 0.1f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = QBXRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(error, color = QBXRed.copy(alpha = 0.9f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        // Confirm dialog
        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                containerColor = QBXSurface,
                titleContentColor = QBXOnSurface,
                textContentColor = QBXOnSurface,
                shape = RoundedCornerShape(16.dp),
                title = { Text(stringResource(R.string.confirm_title), fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        Text(stringResource(R.string.confirm_to), fontSize = 12.sp, color = QBXOnSurfaceDim)
                        Text(toAddress.take(24) + "...", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = QBXOnSurface)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.confirm_amount), fontSize = 12.sp, color = QBXOnSurfaceDim)
                        Text("$amount QBX", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = QBXPurple)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.confirm_fees, feePolicy), fontSize = 12.sp, color = QBXOnSurfaceDim)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirm = false
                            amount.toDoubleOrNull()?.let { amt -> onSend(toAddress, amt, feePolicy) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = QBXPurple),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.btn_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) {
                        Text(stringResource(R.string.btn_cancel), color = QBXOnSurfaceDim)
                    }
                }
            )
        }
    }
}

// ==================== RECEIVE SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    address: String,
    onBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QBXBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.send_back), tint = QBXOnSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.receive_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
        }

        Spacer(Modifier.height(24.dp))

        // QR code area
        Box(
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(QBXSurface),
            contentAlignment = Alignment.Center
        ) {
            val qrBitmap = remember(address) { generateQrCode(address) }
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(12.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Address display
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .solidCard()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.receive_your_address), fontSize = 12.sp, color = QBXOnSurfaceDim)
            Spacer(Modifier.height(8.dp))
            Text(
                address,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = QBXOnSurface,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(20.dp))

        // Copy button
        Button(
            onClick = {
                clipboardManager.setText(AnnotatedString(address))
                copied = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(54.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (copied) QBXGreen else QBXPurple
            )
        ) {
            Icon(
                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (copied) stringResource(R.string.copied) else stringResource(R.string.receive_copy_address),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // Security note
        Text(
            stringResource(R.string.receive_secured),
            fontSize = 12.sp,
            color = QBXOnSurfaceDim
        )
    }
}

fun generateQrCode(text: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}

// ==================== HISTORY SCREEN ====================

@Composable
fun HistoryScreen(
    state: WalletUiState,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QBXBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.send_back), tint = QBXOnSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.history_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.history_count, state.txHistory.size),
                fontSize = 13.sp, color = QBXOnSurfaceDim
            )
        }

        if (state.txHistory.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Receipt,
                    null,
                    tint = QBXOnSurfaceDim.copy(alpha = 0.3f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.history_empty),
                    fontSize = 15.sp,
                    color = QBXOnSurfaceDim,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
            ) {
                items(state.txHistory.size) { index ->
                    val tx = state.txHistory[index]
                    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(QBXSurface)
                            .border(1.dp, QBXDivider.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowUpward, null, tint = QBXRed.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "-${"%.8f".format(tx.amount)} QBX",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = QBXRed.copy(alpha = 0.8f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.history_to), fontSize = 11.sp, color = QBXOnSurfaceDim)
                        Text(
                            tx.toAddress,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = QBXOnSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(stringResource(R.string.history_fee), fontSize = 11.sp, color = QBXOnSurfaceDim)
                                Text(tx.fee, fontSize = 12.sp, color = QBXOnSurface)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(stringResource(R.string.history_date), fontSize = 11.sp, color = QBXOnSurfaceDim)
                                Text(dateFormat.format(Date(tx.timestamp)), fontSize = 12.sp, color = QBXOnSurface)
                            }
                        }
                        if (tx.txid.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("TXID", fontSize = 11.sp, color = QBXOnSurfaceDim)
                            Text(
                                tx.txid,
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                color = QBXOnSurfaceDim.copy(alpha = 0.7f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== SETTINGS SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: WalletUiState,
    onConnect: (String) -> Unit,
    onExportBackup: () -> String,
    onDeleteWallet: () -> Unit,
    onBack: () -> Unit,
    onSetPin: (String) -> Unit,
    onRemovePin: () -> Unit,
    onRenameWallet: (String) -> Unit,
    onDeleteActiveWallet: () -> Unit,
    onSwitchWallet: (Int) -> Unit,
    onAddWallet: (String) -> Unit
) {
    val context = LocalContext.current
    var rpcUrl by remember { mutableStateOf(state.rpcUrl) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = QBXPurple,
        unfocusedBorderColor = QBXDivider,
        focusedContainerColor = QBXSurface,
        unfocusedContainerColor = QBXSurface,
        cursorColor = QBXPurple,
        focusedTextColor = QBXOnSurface,
        unfocusedTextColor = QBXOnSurface,
        focusedLabelColor = QBXPurple,
        unfocusedLabelColor = QBXOnSurfaceDim
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(QBXBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, stringResource(R.string.send_back), tint = QBXOnSurface)
                }
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))

                // Node connection section
                Text(stringResource(R.string.settings_rpc_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_rpc_subtitle), fontSize = 13.sp, color = QBXOnSurfaceDim)

                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = rpcUrl,
                    onValueChange = { rpcUrl = it },
                    label = { Text("RPC URL") },
                    placeholder = { Text("https://qbitx.solopool.site/", color = QBXOnSurfaceDim.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    leadingIcon = { Icon(Icons.Default.Link, null, tint = QBXOnSurfaceDim) }
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { onConnect(rpcUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !state.isLoading && rpcUrl.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QBXPurple,
                        disabledContainerColor = QBXPurple.copy(alpha = 0.3f)
                    )
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = QBXOnSurface, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.settings_connect), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Connection status
                if (state.nodeConnected) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(QBXGreen.copy(alpha = 0.1f))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = QBXGreen, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.settings_connected, state.chain, state.blockHeight.toString()), color = QBXGreen, fontSize = 14.sp)
                    }
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(QBXRed.copy(alpha = 0.12f))
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Default.ErrorOutline, null, tint = QBXRed, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(error, color = QBXRed.copy(alpha = 0.9f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(32.dp))
                HorizontalDivider(color = QBXDivider)
                Spacer(Modifier.height(20.dp))

                // Backup / Export section
                if (state.hasWallet) {
                    Text(stringResource(R.string.settings_backup_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_backup_subtitle), fontSize = 13.sp, color = QBXOnSurfaceDim)

                    Spacer(Modifier.height(16.dp))

                    val clipboardManager = LocalClipboardManager.current
                    var backupKey by remember { mutableStateOf<String?>(null) }
                    var copied by remember { mutableStateOf(false) }
                    var showDeleteConfirm by remember { mutableStateOf(false) }

                    Button(
                        onClick = { backupKey = onExportBackup() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = QBXPurple)
                    ) {
                        Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.settings_show_backup), fontWeight = FontWeight.SemiBold)
                    }

                    backupKey?.let { key ->
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(QBXRed.copy(alpha = 0.1f))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = QBXRed, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_never_share), fontSize = 12.sp, color = QBXRed)
                        }

                        Spacer(Modifier.height(8.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .solidCard()
                                .padding(12.dp)
                        ) {
                            Text(
                                key,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = QBXOnSurface,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(key))
                                copied = true
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                null,
                                tint = if (copied) QBXGreen else QBXOnSurface,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (copied) stringResource(R.string.copied) else stringResource(R.string.settings_copy_backup),
                                color = if (copied) QBXGreen else QBXOnSurface
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = QBXDivider)
                    Spacer(Modifier.height(20.dp))

                    // Delete wallet
                    Text(stringResource(R.string.settings_danger_zone), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QBXRed)
                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = QBXRed),
                        border = androidx.compose.foundation.BorderStroke(1.dp, QBXRed.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.settings_delete_wallet), fontWeight = FontWeight.SemiBold)
                    }

                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            containerColor = QBXSurface,
                            titleContentColor = QBXOnSurface,
                            textContentColor = QBXOnSurface,
                            shape = RoundedCornerShape(16.dp),
                            title = { Text(stringResource(R.string.settings_delete_confirm_title), fontWeight = FontWeight.SemiBold) },
                            text = {
                                Text(
                                    stringResource(R.string.settings_delete_confirm_text),
                                    fontSize = 14.sp
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showDeleteConfirm = false
                                        onDeleteWallet()
                                        onBack()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = QBXRed),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text(stringResource(R.string.settings_delete_yes)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) {
                                    Text(stringResource(R.string.btn_cancel), color = QBXOnSurfaceDim)
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = QBXDivider)
                    Spacer(Modifier.height(20.dp))
                }

                // ===== Security / PIN Section =====
                Text(stringResource(R.string.settings_security), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_security_subtitle), fontSize = 13.sp, color = QBXOnSurfaceDim)
                Spacer(Modifier.height(16.dp))

                var showPinSetup by remember { mutableStateOf(false) }
                var newPin by remember { mutableStateOf("") }
                var confirmPin by remember { mutableStateOf("") }
                var pinError by remember { mutableStateOf("") }

                if (state.hasPin) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .solidCard()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, null, tint = QBXGreen, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.settings_pin_active), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QBXOnSurface, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onRemovePin() },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = QBXRed),
                        border = androidx.compose.foundation.BorderStroke(1.dp, QBXRed.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_remove_pin), fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = { showPinSetup = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = QBXPurple)
                    ) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.settings_setup_pin), fontWeight = FontWeight.SemiBold)
                    }
                }

                if (showPinSetup) {
                    AlertDialog(
                        onDismissRequest = { showPinSetup = false; newPin = ""; confirmPin = ""; pinError = "" },
                        containerColor = QBXSurface,
                        titleContentColor = QBXOnSurface,
                        textContentColor = QBXOnSurface,
                        shape = RoundedCornerShape(16.dp),
                        title = { Text(stringResource(R.string.settings_setup_pin_title), fontWeight = FontWeight.SemiBold) },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = newPin, onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) newPin = it },
                                    label = { Text(stringResource(R.string.settings_new_pin)) }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = QBXPurple, unfocusedBorderColor = QBXDivider,
                                        focusedContainerColor = QBXSurface, unfocusedContainerColor = QBXSurface,
                                        cursorColor = QBXPurple, focusedTextColor = QBXOnSurface, unfocusedTextColor = QBXOnSurface
                                    )
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = confirmPin, onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) confirmPin = it },
                                    label = { Text(stringResource(R.string.settings_confirm_pin)) }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = QBXPurple, unfocusedBorderColor = QBXDivider,
                                        focusedContainerColor = QBXSurface, unfocusedContainerColor = QBXSurface,
                                        cursorColor = QBXPurple, focusedTextColor = QBXOnSurface, unfocusedTextColor = QBXOnSurface
                                    )
                                )
                                if (pinError.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(pinError, color = QBXRed, fontSize = 12.sp)
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    when {
                                        newPin.length != 4 -> pinError = context.getString(R.string.settings_pin_error_length)
                                        newPin != confirmPin -> pinError = context.getString(R.string.settings_pin_error_mismatch)
                                        else -> {
                                            onSetPin(newPin)
                                            showPinSetup = false; newPin = ""; confirmPin = ""; pinError = ""
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = QBXPurple),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text(stringResource(R.string.btn_save)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPinSetup = false; newPin = ""; confirmPin = ""; pinError = "" }) {
                                Text(stringResource(R.string.btn_cancel), color = QBXOnSurfaceDim)
                            }
                        }
                    )
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = QBXDivider)
                Spacer(Modifier.height(20.dp))

                // ===== Wallet Management Section =====
                if (state.wallets.size > 0) {
                    Text(stringResource(R.string.settings_manage_wallets), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_wallets_count, state.wallets.size), fontSize = 13.sp, color = QBXOnSurfaceDim)
                    Spacer(Modifier.height(16.dp))

                    state.wallets.forEach { wallet ->
                        val isActive = wallet.address == state.address
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isActive) QBXPurple.copy(alpha = 0.08f) else QBXSurface)
                                .border(
                                    1.dp,
                                    if (isActive) QBXPurple.copy(alpha = 0.3f) else QBXDivider.copy(alpha = 0.3f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { onSwitchWallet(wallet.id) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isActive) {
                                Icon(Icons.Default.RadioButtonChecked, null, tint = QBXPurple, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Default.RadioButtonUnchecked, null, tint = QBXOnSurfaceDim, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(wallet.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QBXOnSurface)
                                Text(wallet.address.take(16) + "...", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = QBXOnSurfaceDim)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    Spacer(Modifier.height(12.dp))

                    // Rename active wallet
                    var showRename by remember { mutableStateOf(false) }
                    var renameText by remember { mutableStateOf("") }
                    var showAddWallet by remember { mutableStateOf(false) }
                    var addWalletName by remember { mutableStateOf("") }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showRename = true; renameText = state.activeWalletName },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = QBXOnSurface)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_rename), color = QBXOnSurface, fontSize = 13.sp)
                        }
                        if (state.wallets.size > 1) {
                            OutlinedButton(
                                onClick = { onDeleteActiveWallet() },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = QBXRed),
                                border = androidx.compose.foundation.BorderStroke(1.dp, QBXRed.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.settings_remove), fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { showAddWallet = true },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = QBXPurple)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_create_wallet), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (showAddWallet) {
                        AlertDialog(
                            onDismissRequest = { showAddWallet = false; addWalletName = "" },
                            containerColor = QBXSurface,
                            titleContentColor = QBXOnSurface,
                            textContentColor = QBXOnSurface,
                            shape = RoundedCornerShape(16.dp),
                            title = { Text(stringResource(R.string.settings_new_wallet), fontWeight = FontWeight.SemiBold) },
                            text = {
                                OutlinedTextField(
                                    value = addWalletName, onValueChange = { addWalletName = it },
                                    placeholder = { Text("Name (optional)", color = QBXOnSurfaceDim.copy(alpha = 0.5f)) },
                                    singleLine = true, shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = QBXPurple, unfocusedBorderColor = QBXDivider,
                                        focusedContainerColor = QBXSurface, unfocusedContainerColor = QBXSurface,
                                        cursorColor = QBXPurple, focusedTextColor = QBXOnSurface, unfocusedTextColor = QBXOnSurface
                                    )
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = { onAddWallet(addWalletName); showAddWallet = false; addWalletName = "" },
                                    colors = ButtonDefaults.buttonColors(containerColor = QBXPurple),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text(stringResource(R.string.btn_create)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showAddWallet = false; addWalletName = "" }) {
                                    Text(stringResource(R.string.btn_cancel), color = QBXOnSurfaceDim)
                                }
                            }
                        )
                    }

                    if (showRename) {
                        AlertDialog(
                            onDismissRequest = { showRename = false },
                            containerColor = QBXSurface,
                            titleContentColor = QBXOnSurface,
                            textContentColor = QBXOnSurface,
                            shape = RoundedCornerShape(16.dp),
                            title = { Text(stringResource(R.string.settings_rename_wallet), fontWeight = FontWeight.SemiBold) },
                            text = {
                                OutlinedTextField(
                                    value = renameText, onValueChange = { renameText = it },
                                    singleLine = true, shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = QBXPurple, unfocusedBorderColor = QBXDivider,
                                        focusedContainerColor = QBXSurface, unfocusedContainerColor = QBXSurface,
                                        cursorColor = QBXPurple, focusedTextColor = QBXOnSurface, unfocusedTextColor = QBXOnSurface
                                    )
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = { onRenameWallet(renameText); showRename = false },
                                    colors = ButtonDefaults.buttonColors(containerColor = QBXPurple),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text(stringResource(R.string.btn_save)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.btn_cancel), color = QBXOnSurfaceDim) }
                            }
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = QBXDivider)
                    Spacer(Modifier.height(20.dp))
                }

                // About section
                Text(stringResource(R.string.settings_about), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QBXOnSurfaceDim)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.settings_version), fontSize = 15.sp, color = QBXOnSurface)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_subtitle), fontSize = 13.sp, color = QBXOnSurfaceDim)

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
