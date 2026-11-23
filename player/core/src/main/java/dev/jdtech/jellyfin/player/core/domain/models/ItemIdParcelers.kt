package dev.jdtech.jellyfin.player.core.domain.models

import android.os.Parcel
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import kotlinx.parcelize.Parceler

// Parcelize does not write value classes by itself (#773), so a `@Parcelize` class with an
// `ItemId` field names these with `@TypeParceler`. The id goes into the parcel as its string form.

/** Writes an [ItemId] as its string form. */
internal object ItemIdParceler : Parceler<ItemId> {
    override fun create(parcel: Parcel): ItemId = requireNotNull(parcel.readString()).toItemId()

    override fun ItemId.write(parcel: Parcel, flags: Int) = parcel.writeString(asString())
}

/** Writes an [ItemId] that may be missing as its string form, or as a null string. */
internal object NullableItemIdParceler : Parceler<ItemId?> {
    override fun create(parcel: Parcel): ItemId? = parcel.readString()?.toItemId()

    override fun ItemId?.write(parcel: Parcel, flags: Int) = parcel.writeString(this?.asString())
}
