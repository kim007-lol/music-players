// NavControllerUtils.kt

package com.example.musicplaying.ui.utils

import androidx.navigation.NavController
import com.example.musicplaying.ui.screens.Screens

fun NavController.backToMain() {
    val mainRoutes = Screens.MainScreens.map { it.route }

    while (previousBackStackEntry != null &&
        currentBackStackEntry?.destination?.route !in mainRoutes
    ) {
        popBackStack()
    }
}
