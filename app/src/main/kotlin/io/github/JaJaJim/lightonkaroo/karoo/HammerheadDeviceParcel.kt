package io.github.JaJaJim.lightonkaroo.karoo

import android.os.Parcel
import android.os.Parcelable

class HammerheadDeviceParcel(private val uid: String) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(uid)
        dest.writeString("io.hammerhead.datamodels.timeseriesData.models.DeviceInfo")
        dest.writeString("NA") // DeviceInfo.name
        dest.writeString(null) // axsBikeId
        dest.writeInt(0) // syncedWithAxs
        dest.writeInt(0) // nameEditedByUser
        dest.writeInt(0) // lastBatteryValue == null
        dest.writeInt(0) // manufacturerInfo == null
        dest.writeInt(0) // componentInfo == null
        dest.writeList(emptyList<Any>()) // supportedDataTypes
        dest.writeList(emptyList<Any>()) // blockedDataTypes
        dest.writeInt(1) // isEnabled
        dest.writeInt(1) // isConnectedAtLeastOnce
        dest.writeInt(2) // connectionType: OTHER
        dest.writeParcelable(null, 0) // wheelCircumferenceInfo
        dest.writeParcelable(null, 0) // shiftingInfo
        dest.writeInt(-1) // lightMode
        dest.writeInt(-1) // autoLightControl
        dest.writeInt(-1) // ctfOffset
        dest.writeInt(-1) // priority
        dest.writeInt(-1) // functionSets == null
        dest.writeString(null) // axsPrimarySerial
        dest.writeInt(0) // alerts size
        dest.writeIntArray(IntArray(0)) // alerts ordinals
        dest.writeInt(-1) // tirePressureAlerts
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<HammerheadDeviceParcel> {
        override fun createFromParcel(parcel: Parcel): HammerheadDeviceParcel {
            throw UnsupportedOperationException("HammerheadDeviceParcel is write-only for sending commands")
        }

        override fun newArray(size: Int): Array<HammerheadDeviceParcel?> = arrayOfNulls(size)
    }
}
