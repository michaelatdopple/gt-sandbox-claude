package com.dopple.webview.ui.game

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GameUiState(
    val isLoading: Boolean = true,
    val gameName: String = "",
    val error: String? = null
)

class GameViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = loading)
    }

    fun setGameName(name: String) {
        _uiState.value = _uiState.value.copy(gameName = name)
    }

    fun setError(error: String?) {
        _uiState.value = _uiState.value.copy(error = error, isLoading = false)
    }
}
