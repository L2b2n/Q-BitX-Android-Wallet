package org.qbitx.wallet.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.qbitx.wallet.ui.theme.*

// ==================== MAIN WALLET SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    state: WalletUiState,
    onCreateWallet: () -> Unit,
    onNavigateToSend: () -> Unit,
    onNavigateToReceive: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Q-BitX", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Wallet", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!state.hasWallet) {
                // No wallet — show creation screen
                Spacer(Modifier.height(80.dp))
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = QBXBlue
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Post-Quantum Wallet",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Geschützt durch Dilithium3 (ML-DSA)\nQuantencomputer-resistente Signaturen",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = onCreateWallet,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !state.isLoading
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Neues Wallet erstellen", fontSize = 16.sp)
                    }
                }
            } else {
                // Wallet exists — show dashboard
                // Connection status
                ConnectionStatusCard(state)

                Spacer(Modifier.height(16.dp))

                // Balance card
                BalanceCard(state, onRefresh)

                Spacer(Modifier.height(16.dp))

                // Address card
                AddressCard(state.address)

                Spacer(Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onNavigateToSend,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = state.nodeConnected
                    ) {
                        Icon(Icons.Default.Send, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Senden")
                    }
                    OutlinedButton(
                        onClick = onNavigateToReceive,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.QrCode, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Empfangen")
                    }
                }
            }

            // Error display
            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = QBXRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(12.dp),
                        color = QBXRed,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(state: WalletUiState) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.nodeConnected) QBXGreen.copy(alpha = 0.1f) else QBXRed.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (state.nodeConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                null,
                tint = if (state.nodeConnected) QBXGreen else QBXRed,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            if (state.nodeConnected) {
                Text("Verbunden · ${state.chain} · Block ${state.blockHeight}", fontSize = 13.sp)
            } else {
                Text("Nicht verbunden — Einstellungen prüfen", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun BalanceCard(state: WalletUiState, onRefresh: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = QBXSurfaceLight)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Guthaben", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%.8f".format(state.balance),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text("QBX", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = QBXGold)
            }
            if (state.unconfirmedBalance != 0.0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Unbestätigt: ${"%.8f".format(state.unconfirmedBalance)} QBX",
                    fontSize = 12.sp,
                    color = QBXGold
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Aktualisieren", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun AddressCard(address: String) {
    val clipboardManager = LocalClipboardManager.current
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable {
            clipboardManager.setText(AnnotatedString(address))
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Deine PQ-Adresse", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                address,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QBX senden") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // To address
            OutlinedTextField(
                value = toAddress,
                onValueChange = { toAddress = it },
                label = { Text("Empfängeradresse") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Person, null) }
            )

            Spacer(Modifier.height(16.dp))

            // Amount
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Betrag (QBX)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                leadingIcon = { Icon(Icons.Default.Payments, null) }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Verfügbar: ${"%.8f".format(state.balance)} QBX",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(16.dp))

            // Fee policy
            Text("Gebühren", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("low" to "Niedrig\n1 sat/vB", "normal" to "Normal\n5 sat/vB", "high" to "Hoch\n15 sat/vB").forEach { (key, label) ->
                    FilterChip(
                        selected = feePolicy == key,
                        onClick = { feePolicy = key },
                        label = { Text(label, fontSize = 11.sp, textAlign = TextAlign.Center) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Send button
            Button(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = toAddress.isNotBlank() && amount.toDoubleOrNull() != null && amount.toDouble() > 0 && !state.isLoading
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Send, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Senden", fontSize = 16.sp)
                }
            }

            // Success display
            state.lastTxId?.let { txid ->
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = QBXGreen.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Transaktion gesendet!", color = QBXGreen, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("TXID: $txid", fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                    }
                }
            }

            // Error display
            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = QBXRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(error, modifier = Modifier.padding(12.dp), color = QBXRed, fontSize = 13.sp)
                }
            }
        }

        // Confirmation dialog
        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text("Transaktion bestätigen") },
                text = {
                    Column {
                        Text("An: ${toAddress.take(20)}...", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("Betrag: $amount QBX", fontWeight = FontWeight.Bold)
                        Text("Gebühr: $feePolicy", fontSize = 13.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showConfirm = false
                        amount.toDoubleOrNull()?.let { amt ->
                            onSend(toAddress, amt, feePolicy)
                        }
                    }) { Text("Bestätigen") }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QBX empfangen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // QR Code
            val qrBitmap = remember(address) { generateQrCode(address) }
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier
                        .size(250.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(16.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Deine PQ-Adresse",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))

            Card(shape = RoundedCornerShape(8.dp)) {
                Text(
                    address,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { clipboardManager.setText(AnnotatedString(address)) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text("Adresse kopieren")
            }

            Spacer(Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = QBXBlue.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Icon(Icons.Default.Shield, null, tint = QBXBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Post-Quantum-gesichert mit Dilithium3 (ML-DSA-65)",
                        fontSize = 12.sp,
                        color = QBXBlue
                    )
                }
            }
        }
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
    onConnect: (String, Int, String, String, String) -> Unit,
    onBack: () -> Unit
) {
    var host by remember { mutableStateOf(state.nodeHost) }
    var port by remember { mutableStateOf(state.nodePort.toString()) }
    var user by remember { mutableStateOf(state.nodeUser) }
    var password by remember { mutableStateOf(state.nodePassword) }
    var wallet by remember { mutableStateOf(state.walletName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Node-Verbindung", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host / IP-Adresse") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Computer, null) }
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                leadingIcon = { Icon(Icons.Default.Cable, null) }
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("RPC Benutzername") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Person, null) }
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("RPC Passwort") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Lock, null) }
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = wallet,
                onValueChange = { wallet = it },
                label = { Text("Wallet-Name (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.AccountBalanceWallet, null) }
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { onConnect(host, port.toIntOrNull() ?: 8332, user, password, wallet) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !state.isLoading && host.isNotBlank()
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Verbinden")
                }
            }

            if (state.nodeConnected) {
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = QBXGreen.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = QBXGreen, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Verbunden: ${state.chain} · Block #${state.blockHeight}", color = QBXGreen)
                    }
                }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = QBXRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(error, modifier = Modifier.padding(12.dp), color = QBXRed, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(32.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            // About section
            Text("Über", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Q-BitX Wallet v0.2.0", fontSize = 14.sp)
            Text("Post-Quantum Light Wallet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Text("Dilithium3 / ML-DSA-65 Signaturen", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}
