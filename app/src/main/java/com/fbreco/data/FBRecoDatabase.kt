package com.fbreco.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.fbreco.data.dao.DailyRecordDao
import com.fbreco.data.dao.DestinationDao
import com.fbreco.data.entity.Converters
import com.fbreco.data.entity.DailyRecord
import com.fbreco.data.entity.Destination

@Database(
    entities = [DailyRecord::class, Destination::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FBRecoDatabase : RoomDatabase() {
    abstract fun dailyRecordDao(): DailyRecordDao
    abstract fun destinationDao(): DestinationDao
}
