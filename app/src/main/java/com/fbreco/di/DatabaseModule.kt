package com.fbreco.di

import android.content.Context
import androidx.room.Room
import com.fbreco.data.FBRecoDatabase
import com.fbreco.data.dao.DailyRecordDao
import com.fbreco.data.dao.DestinationDao
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
    fun provideDatabase(@ApplicationContext context: Context): FBRecoDatabase {
        return Room.databaseBuilder(
            context,
            FBRecoDatabase::class.java,
            "fbreco_database"
        ).build()
    }

    @Provides
    fun provideDailyRecordDao(database: FBRecoDatabase): DailyRecordDao {
        return database.dailyRecordDao()
    }

    @Provides
    fun provideDestinationDao(database: FBRecoDatabase): DestinationDao {
        return database.destinationDao()
    }
}
