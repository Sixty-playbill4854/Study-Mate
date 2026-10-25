package com.mk.studymate

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** day = ISO yyyy-MM-dd, clock = HH:mm, both wall-clock in the device's current time zone. */
@Entity(tableName = "plans")
data class Plan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val task: String,
    val day: String,
    val clock: String,
    val notes: String = "",
    val completed: Boolean = false
) {
    /** Resolved against the *current* zone, so a time-zone change keeps "9:00 AM" at 9:00 AM. */
    fun startMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(LocalDate.parse(day), LocalTime.parse(clock))
            .atZone(zone).toInstant().toEpochMilli()
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans ORDER BY day, clock") fun observe(): Flow<List<Plan>>
    @Query("SELECT * FROM plans WHERE completed = 0") suspend fun pending(): List<Plan>
    @Query("SELECT * FROM plans WHERE id = :id") suspend fun get(id: Long): Plan?
    @Insert suspend fun insert(p: Plan): Long
    @Update suspend fun update(p: Plan)
    @Delete suspend fun delete(p: Plan)
}

@Database(entities = [Plan::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): PlanDao

    companion object {
        @Volatile private var instance: Db? = null
        fun get(c: Context): Db = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "studymate.db")
                .build().also { instance = it }
        }
    }
}
