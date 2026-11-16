package io.hammerhead.datamodels.timeseriesData.models

import android.os.Parcel
import android.os.Parcelable

class LightMode(
    private val modeName: String,
) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(modeName)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<LightMode> {
            override fun createFromParcel(source: Parcel): LightMode =
                LightMode(source.readString() ?: "UNKNOWN")

            override fun newArray(size: Int): Array<LightMode?> = arrayOfNulls(size)
        }
    }
}
