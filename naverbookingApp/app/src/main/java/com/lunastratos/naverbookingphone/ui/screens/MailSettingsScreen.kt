package com.lunastratos.naverbookingphone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lunastratos.naverbookingphone.data.Settings
import com.lunastratos.naverbookingphone.mail.MailConfig
import com.lunastratos.naverbookingphone.mail.MailSender
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var emailEnabled by remember { mutableStateOf(settings.emailEnabled) }
    var host by remember { mutableStateOf(settings.smtpHost) }
    var port by remember { mutableStateOf(settings.smtpPort.toString()) }
    var ssl by remember { mutableStateOf(settings.smtpSsl) }
    var user by remember { mutableStateOf(settings.smtpUser) }
    var password by remember { mutableStateOf(settings.smtpPassword) }
    var from by remember { mutableStateOf(settings.mailFrom) }
    var to by remember { mutableStateOf(settings.mailTo) }
    var sending by remember { mutableStateOf(false) }

    fun collect() = MailConfig(
        host = host.trim(),
        port = port.toIntOrNull() ?: 465,
        ssl = ssl,
        user = user.trim(),
        password = password,
        from = from.trim(),
        to = to.trim(),
    )

    fun persist() {
        settings.emailEnabled = emailEnabled
        settings.smtpHost = host.trim()
        settings.smtpPort = port.toIntOrNull() ?: 465
        settings.smtpSsl = ssl
        settings.smtpUser = user.trim()
        settings.smtpPassword = password
        settings.mailFrom = from.trim()
        settings.mailTo = to.trim()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("메일 설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("메일 알림", modifier = Modifier.weight(1f))
                Switch(
                    checked = emailEnabled,
                    onCheckedChange = {
                        emailEnabled = it
                        settings.emailEnabled = it
                    },
                )
            }

            HorizontalDivider()

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("SMTP 호스트") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("포트") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SSL", modifier = Modifier.weight(1f))
                    Switch(checked = ssl, onCheckedChange = { ssl = it })
                }
            }
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("사용자(이메일)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("비밀번호 (앱 비밀번호 권장)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = from,
                onValueChange = { from = it },
                label = { Text("From (선택, 비우면 사용자 주소)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = { Text("받는 사람") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "* 네이버: smtp.naver.com / 465 / SSL, 앱 비밀번호 필요\n" +
                "* Gmail: smtp.gmail.com / 465 / SSL, 앱 비밀번호 필요",
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        persist()
                        sending = true
                        scope.launch {
                            val cfg = collect()
                            val result = runCatching {
                                MailSender.send(
                                    cfg,
                                    "[네이버 예약 체크] 테스트 메일",
                                    "테스트 메일입니다.\n설정이 정상이라면 이 메일이 수신됩니다."
                                )
                            }
                            sending = false
                            snackbar.showSnackbar(
                                result.fold(
                                    onSuccess = { "테스트 메일을 보냈습니다." },
                                    onFailure = { "테스트 실패: ${it.message ?: it.javaClass.simpleName}" },
                                )
                            )
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.weight(1f)
                ) { Text(if (sending) "전송 중…" else "테스트 발송") }

                Button(
                    onClick = {
                        persist()
                        scope.launch { snackbar.showSnackbar("저장했습니다.") }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("저장") }
            }
        }
    }
}
