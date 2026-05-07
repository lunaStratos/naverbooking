package com.lunastratos.naverbookingphone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lunastratos.naverbookingphone.data.Settings
import com.lunastratos.naverbookingphone.service.MonitoringService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPickItems: () -> Unit,
    onMailSettings: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val running by MonitoringService.isRunning.collectAsStateWithLifecycle()
    val summary by MonitoringService.lastSummary.collectAsStateWithLifecycle()

    var placeId by remember { mutableStateOf(settings.placeId) }
    var interval by remember { mutableStateOf(settings.intervalSeconds.toString()) }
    var months by remember { mutableStateOf(settings.monthsToCheck.toString()) }
    var selectedCount by remember { mutableStateOf(settings.selectedItems.size) }

    LaunchedEffect(Unit) {
        selectedCount = settings.selectedItems.size
        placeId = settings.placeId
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("네이버 예약 체크") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = placeId,
                onValueChange = { placeId = it.trim() },
                label = { Text("Place ID") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it.filter { c -> c.isDigit() } },
                    label = { Text("주기 (초)") },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = months,
                    onValueChange = { months = it.filter { c -> c.isDigit() } },
                    label = { Text("체크 개월") },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedButton(
                onClick = {
                    settings.placeId = placeId.ifBlank { Settings.DEFAULT_PLACE_ID }
                    onPickItems()
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (selectedCount > 0) "상품 선택 ($selectedCount 선택됨)"
                    else "상품 가져오기 / 선택"
                )
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = onMailSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("메일 설정")
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (running) {
                            MonitoringService.stop(context)
                        } else {
                            settings.placeId = placeId.ifBlank { Settings.DEFAULT_PLACE_ID }
                            settings.intervalSeconds = interval.toIntOrNull() ?: Settings.DEFAULT_INTERVAL
                            settings.monthsToCheck = months.toIntOrNull() ?: Settings.DEFAULT_MONTHS
                            settings.resetBaseline()
                            MonitoringService.start(context)
                        }
                    },
                    enabled = running || selectedCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (running) "중지" else "시작")
                }
                OutlinedButton(
                    onClick = { settings.resetBaseline() },
                    enabled = !running,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("기준 초기화")
                }
            }

            if (selectedCount == 0 && !running) {
                Text(
                    "‘상품 가져오기 / 선택’ 에서 체크할 상품을 골라야 시작할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = if (running) "모니터링 실행 중" else "중지됨",
                style = MaterialTheme.typography.titleMedium,
            )

            if (summary.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
