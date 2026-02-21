package com.todoer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todoer.app.ui.screens.*
import com.todoer.app.ui.theme.ToDoerTheme
import com.todoer.app.viewmodel.Screen
import com.todoer.app.viewmodel.TodoViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            ToDoerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ToDoerNavigation()
                }
            }
        }
    }
}

@Composable
fun ToDoerNavigation(viewModel: TodoViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show errors as snackbar
    LaunchedEffect(state.error) {
        state.error?.let {
            scope.launch {
                snackbarHostState.showSnackbar(it)
                viewModel.clearError()
            }
        }
    }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        when (state.screen) {
            Screen.Setup -> SetupScreen(
                onConnect = viewModel::connect,
                isLoading = state.isLoading,
                errorMessage = state.error
            )

            Screen.Lists -> ListsScreen(
                lists = state.lists,
                isLoading = state.isLoading,
                onListClick = viewModel::selectList,
                onCreateList = viewModel::createList,
                onDeleteList = viewModel::deleteList,
                onRefresh = viewModel::loadLists,
                onSettingsClick = { viewModel.navigateTo(Screen.Settings) }
            )

            Screen.Todos -> TodosScreen(
                listName = state.currentListName,
                todos = state.todos,
                isLoading = state.isLoading,
                onBack = {
                    viewModel.navigateTo(Screen.Lists)
                    viewModel.loadLists()
                },
                onToggle = viewModel::toggleTodo,
                onDelete = viewModel::deleteTodo,
                onAddTodo = viewModel::addTodo,
                onAddSubtask = viewModel::addSubtask,
                onRefresh = viewModel::loadTodos
            )

            Screen.Settings -> SettingsScreen(
                serverUrl = state.serverUrl,
                notificationsEnabled = state.notificationsEnabled,
                onBack = { viewModel.navigateTo(Screen.Lists) },
                onChangeServer = viewModel::changeServer,
                onToggleNotifications = viewModel::toggleNotifications,
                onDisconnect = viewModel::disconnect
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
        )
    }
}
