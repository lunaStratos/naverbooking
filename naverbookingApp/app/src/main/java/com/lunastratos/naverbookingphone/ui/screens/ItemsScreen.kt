package com.lunastratos.naverbookingphone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lunastratos.naverbookingphone.data.SelectedItem
import com.lunastratos.naverbookingphone.data.Settings
import com.lunastratos.naverbookingphone.scraper.BookingItem
import com.lunastratos.naverbookingphone.scraper.NaverBookingScraper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val items = remember { mutableStateListOf<BookingItem>() }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val canSave by remember { derivedStateOf { checked.values.any { it } } }

    LaunchedEffect(Unit) {
        for (sel in settings.selectedItems) checked[sel.url] = true
        loading = true
        error = null
        val scraper = NaverBookingScraper(context)
        try {
            scraper.init()
            val list = scraper.discoverItems(settings.placeId)
            items.clear()
            items.addAll(list)
            for (it in list) checked.putIfAbsent(it.url, false)
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            scraper.destroyOnMain()
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("상품 선택") },
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
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { for (it in items) checked[it.url] = true },
                    enabled = items.isNotEmpty()
                ) { Text("모두 선택") }
                OutlinedButton(
                    onClick = { for (it in items) checked[it.url] = false },
                    enabled = items.isNotEmpty()
                ) { Text("모두 해제") }
                Spacer(Modifier.weight(1f))
                Text(
                    "${checked.count { it.value }} / ${items.size}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            HorizontalDivider()

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("상품 목록을 가져오는 중…")
                    }
                    error != null -> Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("불러오기 실패: $error", style = MaterialTheme.typography.bodyMedium)
                    }
                    items.isEmpty() -> Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("상품을 찾지 못했습니다. Place ID를 확인하세요.")
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items, key = { it.url }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked[item.url] == true,
                                    onCheckedChange = { checked[item.url] = it }
                                )
                                Text(
                                    text = item.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) { Text("취소") }
                Button(
                    onClick = {
                        val byUrl = items.associateBy { it.url }
                        val sel = checked.entries
                            .filter { it.value }
                            .mapNotNull { byUrl[it.key] }
                            .map { SelectedItem(it.title, it.url) }
                        settings.selectedItems = sel
                        scope.launch {
                            snackbar.showSnackbar("${sel.size}개 상품을 저장했습니다.")
                        }
                        onBack()
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f)
                ) { Text("선택 저장") }
            }
        }
    }
}
