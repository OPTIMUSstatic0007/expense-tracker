package com.example.expensetracker.cloud

import androidx.room.TypeConverter

class PendingSyncConverters {
    @TypeConverter
    fun operationTypeToString(value: PendingSyncOperation.OperationType): String = value.name

    @TypeConverter
    fun stringToOperationType(value: String): PendingSyncOperation.OperationType {
        return PendingSyncOperation.OperationType.valueOf(value)
    }

    @TypeConverter
    fun statusToString(value: PendingSyncOperation.Status): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): PendingSyncOperation.Status {
        return PendingSyncOperation.Status.valueOf(value)
    }
}
