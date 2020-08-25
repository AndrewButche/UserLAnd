package tech.ula.model.entities

import android.arch.persistence.room.Entity
import android.arch.persistence.room.PrimaryKey
import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
@Entity(tableName = "filesystem")
data class Filesystem(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    var name: String = "",
    var distributionType: String = "",
    var archType: String = "",
    var defaultUsername: String = "",
    var defaultPassword: String = "",
    var defaultVncPassword: String = "",
    val isAppsFilesystem: Boolean = false,
    var lastUpdated: Long = -1L,
    var isCreatedFromBackup: Boolean = false
) : Parcelable {
    override fun toString(): String {
        return "Filesystem(id=$id, name=$name, distributionType=$distributionType, archType=" +
                "$archType, isAppsFilesystem=$isAppsFilesystem, lastUpdated=$lastUpdated"
    }
}