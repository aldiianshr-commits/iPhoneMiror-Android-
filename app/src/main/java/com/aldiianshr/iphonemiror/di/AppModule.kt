package com.aldiianshr.iphonemiror.di

import android.content.Context
import com.aldiianshr.iphonemiror.connection.ReceiverController
import com.aldiianshr.iphonemiror.data.db.AppDatabase
import com.aldiianshr.iphonemiror.data.identity.KeystoreIdentityStore
import com.aldiianshr.iphonemiror.data.repository.RoomHistoryRepository
import com.aldiianshr.iphonemiror.data.repository.RoomKnownDeviceRepository
import com.aldiianshr.iphonemiror.data.settings.DataStoreSettingsRepository
import com.aldiianshr.iphonemiror.discovery.AirPlayAdvertiser
import com.aldiianshr.iphonemiror.discovery.AppleNetworkBrowser
import com.aldiianshr.iphonemiror.discovery.BluetoothAppleScanner
import com.aldiianshr.iphonemiror.discovery.UsbAppleMonitor
import com.aldiianshr.iphonemiror.domain.repository.HistoryRepository
import com.aldiianshr.iphonemiror.domain.repository.IdentityStore
import com.aldiianshr.iphonemiror.domain.repository.KnownDeviceRepository
import com.aldiianshr.iphonemiror.domain.repository.SettingsRepository
import com.aldiianshr.iphonemiror.input.BluetoothHidController
import com.aldiianshr.iphonemiror.mirroring.MirrorEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the feature modules together. Library modules stay DI-agnostic (plain
 * constructors) so they can be unit-tested without Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): AppDatabase = AppDatabase.create(ctx)

    @Provides @Singleton
    fun historyRepository(db: AppDatabase): RoomHistoryRepository = RoomHistoryRepository(db.history())

    @Provides @Singleton
    fun history(repo: RoomHistoryRepository): HistoryRepository = repo

    @Provides @Singleton
    fun knownDevices(db: AppDatabase): KnownDeviceRepository = RoomKnownDeviceRepository(db.knownDevices())

    @Provides @Singleton
    fun settings(@ApplicationContext ctx: Context): SettingsRepository = DataStoreSettingsRepository(ctx)

    @Provides @Singleton
    fun identity(@ApplicationContext ctx: Context): IdentityStore = KeystoreIdentityStore(ctx)

    @Provides @Singleton
    fun advertiser(@ApplicationContext ctx: Context) = AirPlayAdvertiser(ctx)

    @Provides @Singleton
    fun browser(@ApplicationContext ctx: Context) = AppleNetworkBrowser(ctx)

    @Provides @Singleton
    fun usb(@ApplicationContext ctx: Context) = UsbAppleMonitor(ctx)

    @Provides @Singleton
    fun bleScanner(@ApplicationContext ctx: Context) = BluetoothAppleScanner(ctx)

    @Provides @Singleton
    fun hid(@ApplicationContext ctx: Context) = BluetoothHidController(ctx)

    @Provides @Singleton
    fun mirrorEngine(holder: SettingsHolder) = MirrorEngine(
        lowLatency = { holder.current.lowLatencyDecoder },
        audioEnabled = { holder.current.audioEnabled },
    )

    @Provides @Singleton
    fun receiverController(
        @ApplicationContext ctx: Context,
        advertiser: AirPlayAdvertiser,
        identity: IdentityStore,
        history: HistoryRepository,
        known: KnownDeviceRepository,
        engine: MirrorEngine,
    ) = ReceiverController(ctx, advertiser, identity, history, known, engine, engine)
}
