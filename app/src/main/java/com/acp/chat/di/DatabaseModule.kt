package com.acp.chat.di

import android.content.Context
import androidx.room.Room
import com.acp.chat.data.local.AgentDao
import com.acp.chat.data.local.AppDatabase
import com.acp.chat.data.local.MessageDao
import com.acp.chat.data.local.TransportEndpointDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "acp_chat_database"
        )
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideAgentDao(database: AppDatabase): AgentDao = database.agentDao()

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideTransportEndpointDao(database: AppDatabase): TransportEndpointDao =
        database.transportEndpointDao()
}
