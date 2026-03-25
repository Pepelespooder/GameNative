package app.gamenative.db.converters

import androidx.room.TypeConverter
import app.gamenative.data.WorkshopItemState

class WorkshopItemConverter {
    @TypeConverter
    fun fromWorkshopItemState(state: WorkshopItemState): String = state.name

    @TypeConverter
    fun toWorkshopItemState(value: String): WorkshopItemState =
        WorkshopItemState.valueOf(value)
}
