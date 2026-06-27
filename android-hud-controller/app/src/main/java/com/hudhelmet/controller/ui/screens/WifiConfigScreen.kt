package com.hudhelmet.controller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hudhelmet.controller.model.WifiNetwork
import com.hudhelmet.controller.ui.theme.*
import com.hudhelmet.controller.viewmodel.HudViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiConfigScreen(
    viewModel: HudViewModel,
    onNavigateBack: () -> Unit
) {
    val scannedNetworks by viewModel.scannedNetworks.collectAsState()
    val savedNetworks by viewModel.savedNetworks.collectAsState()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var selectedSsid by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.getSavedWifi()
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Cấu hình Wi-Fi Mũ", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val connectionState by viewModel.connectionState.collectAsState()
            
            if (connectionState != com.hudhelmet.controller.model.ConnectionState.CONNECTED) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Chưa kết nối với ESP32.", color = HudRed, fontWeight = FontWeight.Bold)
                        Text("Nếu bạn đang kết nối với Wi-Fi HOD_Helmet của Mũ, hãy nhấn nút dưới đây để cấu hình:", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.connectToApMode() },
                            colors = ButtonDefaults.buttonColors(containerColor = HudYellow),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Kết nối qua IP Mũ (192.168.4.1)", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
            Button(
                onClick = { viewModel.scanWifi() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = HudCyan)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Quét mạng Wi-Fi", fontWeight = FontWeight.Bold)
            }

            if (savedNetworks.isNotEmpty()) {
                Text("Mạng đã lưu trên ESP32:", color = HudYellow, fontWeight = FontWeight.Bold)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(savedNetworks) { net ->
                        SavedNetworkItem(
                            network = net,
                            onDelete = { viewModel.deleteSavedWifi(net.ssid) }
                        )
                    }
                }
            }

            if (scannedNetworks.isNotEmpty()) {
                Text("Mạng tìm thấy:", color = HudCyan, fontWeight = FontWeight.Bold)
                LazyColumn(modifier = Modifier.weight(2f)) {
                    items(scannedNetworks) { net ->
                        ScannedNetworkItem(
                            network = net,
                            onClick = {
                                selectedSsid = net.ssid
                                passwordInput = ""
                                showPasswordDialog = true
                            }
                        )
                    }
                }
            }

            Button(
                onClick = { 
                    selectedSsid = ""
                    passwordInput = ""
                    showPasswordDialog = true 
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Thêm mạng thủ công (Mạng ẩn)", color = TextPrimary)
            }
        }
    }
}

    if (showPasswordDialog) {
        val isManual = selectedSsid.isEmpty()
        var manualSsid by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text(if (isManual) "Kết nối mạng thủ công" else "Kết nối tới $selectedSsid") },
            text = {
                Column {
                    if (isManual) {
                        OutlinedTextField(
                            value = manualSsid,
                            onValueChange = { manualSsid = it },
                            label = { Text("Tên mạng (SSID)") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Mật khẩu") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val finalSsid = if (isManual) manualSsid.trim() else selectedSsid
                    if (finalSsid.isNotEmpty()) {
                        viewModel.connectEspWifi(finalSsid, passwordInput)
                        showPasswordDialog = false
                    }
                }) {
                    Text("Kết nối")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }
}

@Composable
fun ScannedNetworkItem(network: WifiNetwork, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Wifi, contentDescription = null, tint = HudCyan)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(network.ssid, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("Tín hiệu: ${network.rssi} dBm", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SavedNetworkItem(network: WifiNetwork, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Save, contentDescription = null, tint = HudYellow)
            Spacer(modifier = Modifier.width(16.dp))
            Text(network.ssid, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = HudMagenta)
            }
        }
    }
}
