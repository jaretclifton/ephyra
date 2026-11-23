package dev.jdtech.jellyfin.setup.presentation.addresses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.AddressRejected
import dev.jdtech.jellyfin.setup.domain.LocalNetworkAccess
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.domain.blocks
import dev.jdtech.jellyfin.utils.LoadErrorText
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class ServerAddressesViewModel
@Inject
constructor(
    private val repository: SetupRepository,
    private val localNetworkAccess: LocalNetworkAccess,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerAddressesState())
    val state = _state.asStateFlow()

    private var currentServerId: ServerId = "".toServerId()

    fun loadAddresses(serverId: ServerId) {
        currentServerId = serverId
        viewModelScope.launch {
            try {
                _state.emit(ServerAddressesState(addresses = repository.getAddresses(serverId)))
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun deleteAddress(addressId: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.emit(_state.value.copy(error = null))
            val currentAddress = repository.getCurrentAddress(currentServerId)
            if (addressId == currentAddress?.id) {
                // CE (#390): say why instead of dropping the removal without a word.
                setError(SetupR.string.ce_server_address_in_use)
                return@launch
            }
            repository.deleteAddress(addressId)
            loadAddresses(currentServerId)
        }
    }

    fun addAddress(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.emit(_state.value.copy(error = null))
            try {
                repository.addAddress(currentServerId, address)
                loadAddresses(currentServerId)
            } catch (e: AddressRejected) {
                when (e.reason) {
                    AddressRejected.Reason.NOT_FOUND ->
                        // CE (#376): an address on the local network, while access to it is
                        // denied, names the permission.
                        setError(
                            if (localNetworkAccess.blocks(address)) {
                                SetupR.string.ce_add_server_error_local_network
                            } else {
                                SetupR.string.add_server_error_not_found
                            }
                        )
                    // CE (#390): the dialog has closed, so say why nothing was added.
                    AddressRejected.Reason.ALREADY_SAVED ->
                        setError(SetupR.string.ce_server_address_already_added)
                }
            } catch (e: Exception) {
                Timber.e(e)
                // CE (#606): plain words, never the exception's own text (#377).
                _state.emit(_state.value.copy(error = listOf(LoadErrorText.reason(e))))
            }
        }
    }

    private suspend fun setError(resId: Int) {
        _state.emit(_state.value.copy(error = listOf(UiText.StringResource(resId))))
    }

    fun onAction(action: ServerAddressesAction) {
        when (action) {
            is ServerAddressesAction.AddAddress -> {
                addAddress(action.address)
            }
            is ServerAddressesAction.DeleteAddress -> {
                deleteAddress(action.addressId)
            }
            else -> Unit
        }
    }
}
