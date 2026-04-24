package com.munchkin.app

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munchkin.app.ui.navigation.MunchkinNavHost
import com.munchkin.app.ui.screens.TutorialPrefs
import com.munchkin.app.ui.screens.TutorialScreen
import com.munchkin.app.ui.theme.MunchkinTheme
import com.munchkin.app.util.LocaleManager
import com.munchkin.app.viewmodel.AccountViewModel
import com.munchkin.app.viewmodel.CatalogViewModel
import com.munchkin.app.viewmodel.GameDirectoryViewModel
import com.munchkin.app.viewmodel.GameUiEvent
import com.munchkin.app.viewmodel.GameViewModel
import com.munchkin.app.viewmodel.HistoryViewModel
import com.munchkin.app.viewmodel.UpdateViewModel

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val viewModel: GameViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            val accountViewModel: AccountViewModel = viewModel()
            val accountState by accountViewModel.uiState.collectAsState()
            val catalogViewModel: CatalogViewModel = viewModel()
            val catalogState by catalogViewModel.uiState.collectAsState()
            val historyViewModel: HistoryViewModel = viewModel()
            val historyState by historyViewModel.uiState.collectAsState()
            val gameDirectoryViewModel: GameDirectoryViewModel = viewModel()
            val gameDirectoryState by gameDirectoryViewModel.uiState.collectAsState()
            val updateViewModel: UpdateViewModel = viewModel()
            val updateState by updateViewModel.uiState.collectAsState()

            MunchkinTheme {
                val context = androidx.compose.ui.platform.LocalContext.current
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        val message = when (event) {
                            is GameUiEvent.Reconnected -> context.getString(R.string.reconnected)
                            is GameUiEvent.ShowError -> event.message
                            is GameUiEvent.ShowMessage -> event.message
                            is GameUiEvent.ShowSuccess -> event.message
                            is GameUiEvent.Navigate -> null
                            GameUiEvent.PlaySound -> null
                        }
                        message?.let {
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                LaunchedEffect(updateState.message) {
                    val message = updateState.message ?: return@LaunchedEffect
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    updateViewModel.clearMessage()
                }

                LaunchedEffect(accountState.userProfile?.id) {
                    if (accountState.userProfile != null) {
                        gameDirectoryViewModel.loadHostedGames()
                    } else {
                        gameDirectoryViewModel.clear()
                        catalogViewModel.clear()
                    }
                }

                DisposableEffect(lifecycleOwner, accountState.userProfile?.id) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.checkReconnection(accountState.userProfile)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                var showTutorial by remember {
                    mutableStateOf(!TutorialPrefs.isShown(this@MainActivity))
                }

                if (showTutorial) {
                    TutorialScreen(
                        onFinish = {
                            TutorialPrefs.markShown(this@MainActivity)
                            showTutorial = false
                        }
                    )
                } else {
                    MunchkinNavHost(
                        viewModel = viewModel,
                        uiState = uiState,
                        accountViewModel = accountViewModel,
                        accountState = accountState,
                        catalogViewModel = catalogViewModel,
                        catalogState = catalogState,
                        historyViewModel = historyViewModel,
                        historyState = historyState,
                        gameDirectoryViewModel = gameDirectoryViewModel,
                        gameDirectoryState = gameDirectoryState,
                        updateViewModel = updateViewModel,
                        updateState = updateState
                    )
                }
            }
        }
    }
}
