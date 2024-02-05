package fi.jannetahkola.mobilecomputing.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user")
data class User(
    @PrimaryKey
    val uid: Int,

    val username: String?,

    @ColumnInfo(name = "user_image")
    val userImage: String?
)
