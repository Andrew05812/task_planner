package com.taskplanner.di

import com.taskplanner.data.auth.CredentialManagerSignInTransport
import com.taskplanner.data.auth.GoogleSignInTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The credential exchange the app actually runs.
 *
 * One binding, and it is the production one: `CredentialManagerSignInTransport` performs the real
 * Credential Manager request and the real Firebase exchange. A test that wants to drive the sign-in state
 * machine constructs `AuthRepository` with its own transport (see `AuthRepositoryTest`) — it does not go
 * through Hilt, and nothing in this module is swapped out at run time.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindSignInTransport(
        impl: CredentialManagerSignInTransport
    ): GoogleSignInTransport
}
