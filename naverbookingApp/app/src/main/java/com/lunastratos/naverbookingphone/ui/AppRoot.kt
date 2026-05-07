package com.lunastratos.naverbookingphone.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lunastratos.naverbookingphone.ui.screens.HomeScreen
import com.lunastratos.naverbookingphone.ui.screens.ItemsScreen
import com.lunastratos.naverbookingphone.ui.screens.MailSettingsScreen

private object Routes {
    const val HOME = "home"
    const val ITEMS = "items"
    const val MAIL = "mail"
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onPickItems = { nav.navigate(Routes.ITEMS) },
                onMailSettings = { nav.navigate(Routes.MAIL) },
            )
        }
        composable(Routes.ITEMS) {
            ItemsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.MAIL) {
            MailSettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
