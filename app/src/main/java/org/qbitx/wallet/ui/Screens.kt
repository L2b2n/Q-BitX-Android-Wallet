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
import androidx.compose.ui.text.AnnotatedString
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
import org.qbitx.wallet.ui.theme.*

// ==================== CARD STYLE ====================

val BalanceGradient = Brush.linearGradient(
    colors = listOf(QBXPurple, QBXBlueDark),
    start = Offset(0f, 0f),
    end = Offset(800f, 400f)
)

private fun Modifier.solidCard(): Modifier = this
    .clip(RoundedCornerShape(16.dp))
    .background(QBXSurface)

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
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QBXBackground)
            .statusBarsPadding()
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
                "Willkommen bei Q-BitX",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = QBXOnSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Quantencomputer-resistente\nKryptowährung der Zukunft",
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
                    Text("Wallet erstellen", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                Text("Wallet importieren", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            if (showImport) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = importKey,
                    onValueChange = { importKey = it },
                    placeholder = { Text("Backup-Key einfügen...", color = QBXOnSurfaceDim.copy(alpha = 0.5f)) },
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
                    Text("Importieren", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Feature list
            listOf(
                Triple(Icons.Default.Shield, "Dilithium3 / ML-DSA-65", "Post-Quantum Signaturen"),
                Triple(Icons.Default.Lock, "AES-256 verschlüsselt", "Schlüssel sicher gespeichert"),
                Triple(Icons.Default.Speed, "SHA-256d Proof-of-Work", "Bewährter Konsens-Mechanismus")
            ).forEach { (icon, title, subtitle) ->
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
                        Text("Guthaben", fontSize = 14.sp, color = QBXOnSurface.copy(alpha = 0.7f))
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
                            "Unbestätigt: ${"%.8f".format(state.unconfirmedBalance)} QBX",
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
                    Text("Senden", fontSize = 13.sp, color = QBXOnSurface)
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
                    Text("Empfangen", fontSize = 13.sp, color = QBXOnSurface)
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
                    Text("Refresh", fontSize = 13.sp, color = QBXOnSurface)
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
                        if (state.nodeConnected) "Verbunden" else "Nicht verbunden",
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
            Text("PQ-Adresse", fontSize = 11.sp, color = QBXOnSurfaceDim)
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
                Icon(Icons.Default.ArrowBack, "Zurück", tint = QBXOnSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text("QBX Senden", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
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
                        "Verfügbar: ${"%.8f".format(state.balance)} QBX",
                        fontSize = 12.sp,
                        color = QBXOnSurfaceDim
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // To Address input
            Text("Empfänger", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QBXOnSurfaceDim)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = toAddress,
                onValueChange = { toAddress = it },
                placeholder = { Text("M... Adresse eingeben", color = QBXOnSurfaceDim.copy(alpha = 0.5f)) },
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
                            setPrompt("QR-Code scannen")
                            setBeepEnabled(false)
                            setOrientationLocked(true)
                        }
                        scanLauncher.launch(options)
                    }) {
                        Icon(Icons.Default.QrCodeScanner, "QR scannen", tint = QBXPurple)
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // Amount input
            Text("Betrag", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QBXOnSurfaceDim)
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
            Text("Gebühren", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QBXOnSurfaceDim)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    Triple("low", "Niedrig", "1 sat/vB"),
                    Triple("normal", "Normal", "5 sat/vB"),
                    Triple("high", "Hoch", "15 sat/vB")
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
                    Text("Senden", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                        Text("Transaktion gesendet!", color = QBXGreen, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                title = { Text("Transaktion bestätigen", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        Text("An:", fontSize = 12.sp, color = QBXOnSurfaceDim)
                        Text(toAddress.take(24) + "...", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = QBXOnSurface)
                        Spacer(Modifier.height(8.dp))
                        Text("Betrag:", fontSize = 12.sp, color = QBXOnSurfaceDim)
                        Text("$amount QBX", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = QBXPurple)
                        Spacer(Modifier.height(4.dp))
                        Text("Gebühren: $feePolicy", fontSize = 12.sp, color = QBXOnSurfaceDim)
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
                    ) { Text("Bestätigen") }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) {
                        Text("Abbrechen", color = QBXOnSurfaceDim)
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
            .statusBarsPadding(),
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
                Icon(Icons.Default.ArrowBack, "Zurück", tint = QBXOnSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text("QBX Empfangen", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
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
            Text("Deine PQ-Adresse", fontSize = 12.sp, color = QBXOnSurfaceDim)
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
                if (copied) "Kopiert!" else "Adresse kopieren",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // Security note
        Text(
            "Dilithium3 / ML-DSA-65 gesichert",
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

// ==================== SETTINGS SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: WalletUiState,
    onConnect: (String) -> Unit,
    onExportBackup: () -> String,
    onDeleteWallet: () -> Unit,
    onBack: () -> Unit
) {
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
                    Icon(Icons.Default.ArrowBack, "Zurück", tint = QBXOnSurface)
                }
                Spacer(Modifier.width(4.dp))
                Text("Einstellungen", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))

                // Node connection section
                Text("RPC Verbindung", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
                Spacer(Modifier.height(4.dp))
                Text("Stateless RPC — keine Wallet-Daten auf dem Server", fontSize = 13.sp, color = QBXOnSurfaceDim)

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
                        Text("Verbinden", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                        Text("Verbunden: ${state.chain} · Block #${state.blockHeight}", color = QBXGreen, fontSize = 14.sp)
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
                    Text("Wallet-Backup", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QBXOnSurface)
                    Spacer(Modifier.height(4.dp))
                    Text("Speichere deinen Backup-Key um das Wallet wiederherstellen zu können", fontSize = 13.sp, color = QBXOnSurfaceDim)

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
                        Text("Backup-Key anzeigen", fontWeight = FontWeight.SemiBold)
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
                            Text("Niemals teilen! Wer diesen Key hat, kontrolliert dein Wallet.", fontSize = 12.sp, color = QBXRed)
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
                                if (copied) "Kopiert!" else "Backup-Key kopieren",
                                color = if (copied) QBXGreen else QBXOnSurface
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = QBXDivider)
                    Spacer(Modifier.height(20.dp))

                    // Delete wallet
                    Text("Gefahrenzone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QBXRed)
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
                        Text("Wallet löschen", fontWeight = FontWeight.SemiBold)
                    }

                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            containerColor = QBXSurface,
                            titleContentColor = QBXOnSurface,
                            textContentColor = QBXOnSurface,
                            shape = RoundedCornerShape(16.dp),
                            title = { Text("Wallet wirklich löschen?", fontWeight = FontWeight.SemiBold) },
                            text = {
                                Text(
                                    "Dein Wallet wird unwiderruflich gelöscht. Stelle sicher, dass du deinen Backup-Key gespeichert hast!",
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
                                ) { Text("Ja, löschen") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) {
                                    Text("Abbrechen", color = QBXOnSurfaceDim)
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = QBXDivider)
                    Spacer(Modifier.height(20.dp))
                }

                // About section
                Text("Über", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QBXOnSurfaceDim)
                Spacer(Modifier.height(10.dp))
                Text("Q-BitX Wallet v0.2.0", fontSize = 15.sp, color = QBXOnSurface)
                Spacer(Modifier.height(4.dp))
                Text("Post-Quantum Light Wallet · Dilithium3 / ML-DSA-65", fontSize = 13.sp, color = QBXOnSurfaceDim)

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
