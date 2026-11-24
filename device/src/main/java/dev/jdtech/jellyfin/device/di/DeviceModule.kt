package dev.jdtech.jellyfin.device.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.domain.ports.DeviceCodecs
import dev.jdtech.jellyfin.player.DeviceCodecProbe
import dev.jdtech.jellyfin.utils.NetworkConnectivity
import dev.jdtech.jellyfin.utils.NetworkConnectivityImpl
import javax.inject.Singleton

/**
 * The adapters over the device (#965): the codec probe, moved from `data`'s
 * `MediaRepositoryModule`, and the connectivity check, moved from `core`'s `NetworkModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    @Singleton
    @Provides
    fun provideDeviceCodecs(application: Application): DeviceCodecs = DeviceCodecProbe(application)

    @Singleton
    @Provides
    fun provideNetworkConnectivity(impl: NetworkConnectivityImpl): NetworkConnectivity = impl
}
