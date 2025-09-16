package com.ai.growsight.data

import androidx.room.TypeConverter
import java.util.Date

class Converters {

    // Date <-> Timestamp
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    // List<String> <-> String
    @TypeConverter
    fun fromStringList(value: String?): List<String> {
        return value?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    @TypeConverter
    fun toStringList(list: List<String>?): String {
        return list?.joinToString(",") ?: ""
    }
}
