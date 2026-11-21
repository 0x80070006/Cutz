package fr.cutz.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Enums are persisted by name through Room's built-in enum support (since 2.3),
// so no TypeConverter is needed and the values stay readable in exported schemas.
@Database(entities = [ShortcutEntity::class], version = 1, exportSchema = true)
abstract class CutzDatabase : RoomDatabase() {
    abstract fun shortcutDao(): ShortcutDao

    companion object {
        fun build(context: Context): CutzDatabase =
            Room.databaseBuilder(context, CutzDatabase::class.java, "cutz.db").build()
    }
}
