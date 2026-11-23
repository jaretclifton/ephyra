package dev.jdtech.jellyfin.setup.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ServersViewModel
@Inject
constructor(private val repository: SetupRepository, private val appPreferences: AppPreferences) :
    ViewModel() {
    private val _state = MutableStateFlow(ServersState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<ServersEvent>()
    val events = eventsChannel.receiveAsFlow()

    fun loadServers() {
        viewModelScope.launch {
            val servers = repository.getServers()
            _state.emit(ServersState(servers = servers))
        }
    }

    private fun setCurrentServer(serverId: ServerId) {
        viewModelScope.launch {
            repository.setCurrentServer(serverId)

            appPreferences.setCurrentServerId(serverId)

            eventsChannel.send(ServersEvent.ServerChanged)
        }
    }

    private fun setCurrentAddress(addressId: ServerAddressId) {
        viewModelScope.launch {
            repository.setCurrentAddress(addressId)

            eventsChannel.send(ServersEvent.AddressChanged)
        }
    }

    private fun deleteServer(serverId: ServerId) {
        viewModelScope.launch {
            repository.deleteServer(serverId)
            loadServers()
        }
    }

    fun onAction(action: ServersAction) {
        when (action) {
            is ServersAction.OnServerClick -> {
                setCurrentServer(action.serverId)
            }
            is ServersAction.OnAddressClick -> {
                setCurrentAddress(action.addressId)
            }
            is ServersAction.DeleteServer -> {
                deleteServer(action.serverId)
            }
            else -> Unit
        }
    }
}
