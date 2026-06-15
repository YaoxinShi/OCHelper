package com.ochelper.ui.ocnode

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ochelper.data.AppPreferences
import com.ochelper.ocnode.OCNodeState
import com.ochelper.service.OCNodeService
import com.ochelper.service.ServiceRegistry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class OCNodeViewModel(private val context: Context) : ViewModel() {
    private val prefs = AppPreferences(context)

    val serverUrl = prefs.ocNodeUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val authToken = prefs.ocNodeToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val nodeState: StateFlow<OCNodeState> = flow {
        while (true) {
            val client = ServiceRegistry.ocNodeClient
            emit(client?.state?.value ?: OCNodeState.Disconnected)
            kotlinx.coroutines.delay(500)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OCNodeState.Disconnected)

    fun saveAndConnect(url: String, token: String) {
        viewModelScope.launch {
            prefs.setOcNodeUrl(url)
            prefs.setOcNodeToken(token)
            prefs.setOcNodeEnabled(true)
            OCNodeService.start(context)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            prefs.setOcNodeEnabled(false)
            OCNodeService.stop(context)
        }
    }
}
