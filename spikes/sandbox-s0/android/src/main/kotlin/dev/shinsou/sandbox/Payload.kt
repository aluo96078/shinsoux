package dev.shinsou.sandbox

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.SharedMemory

/**
 * A byte string crossing binder. Small values travel inline; large ones travel as a pipe stream or
 * a read-only SharedMemory region (both are fds, so they bypass the ~1 MB transaction limit).
 */
class Payload private constructor(
    val inline: ByteArray?,
    val pipe: ParcelFileDescriptor?,
    val shm: SharedMemory?,
    val length: Int,
) : Parcelable {
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(length)
        dest.writeByteArray(inline)
        dest.writeParcelable(pipe, flags)
        dest.writeParcelable(shm, flags)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<Payload> {
            override fun createFromParcel(p: Parcel): Payload {
                val len = p.readInt()
                val inline = p.createByteArray()
                val pipe = p.readParcelable<ParcelFileDescriptor>(ParcelFileDescriptor::class.java.classLoader)
                val shm = p.readParcelable<SharedMemory>(SharedMemory::class.java.classLoader)
                return Payload(inline, pipe, shm, len)
            }

            override fun newArray(size: Int): Array<Payload?> = arrayOfNulls(size)
        }

        fun inline(bytes: ByteArray) = Payload(bytes, null, null, bytes.size)
        fun pipe(read: ParcelFileDescriptor, length: Int) = Payload(null, read, null, length)
        fun shm(region: SharedMemory, length: Int) = Payload(null, null, region, length)
    }
}
