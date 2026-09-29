package dev.handeye.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FeedUiState(
    val isLoading: Boolean = false,
    val items: List<FeedItem> = emptyList(),
    val error: String? = null,
)

class FeedViewModel(private val repo: FeedRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState

    init {
        // 冷启动：内存/持久化有数据就直接恢复，不触发网络请求。
        viewModelScope.launch {
            repo.loadInitial()?.let { items ->
                _uiState.value = FeedUiState(isLoading = false, items = items)
            }
        }
    }

    fun onRefresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { repo.refresh() }
                .onSuccess { items ->
                    _uiState.value = FeedUiState(isLoading = false, items = items)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
        }
    }

    fun onToggleLike(id: Int) {
        viewModelScope.launch {
            repo.toggleLike(id)?.let { updated ->
                _uiState.value = _uiState.value.copy(
                    items = _uiState.value.items.map { if (it.id == id) updated else it },
                )
            }
        }
    }

    fun onClearAll() {
        viewModelScope.launch {
            repo.clearAll()
            _uiState.value = FeedUiState()
        }
    }

    fun onDropMemoryCache() {
        viewModelScope.launch { repo.dropMemoryCache() }
    }
}
