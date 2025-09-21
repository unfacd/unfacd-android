package com.unfacd.android.ui.components.intro_contact

import android.os.Parcelable
import android.text.TextUtils
import com.unfacd.android.locallyaddressable.LocallyAddressable
import kotlinx.parcelize.Parcelize

@Parcelize
class IntroContactDescriptor(val addressable: LocallyAddressable?, val handle: String?, val message: String?, val avatarId: String?, val introDirection: IntroDirection?, val timestampSent: Long, val responseStatus: ResponseStatus?, val timestampResponse: Long, val eid: Long) : Parcelable {
    var avatarBlob: ByteArray? = null

    fun isIgnored(): Boolean {
        return responseStatus == ResponseStatus.IGNORED
    }

    fun isAccepted(): Boolean {
        return responseStatus == ResponseStatus.ACCEPTED
    }

    fun isRejected(): Boolean {
        return responseStatus == ResponseStatus.REJECTED
    }

    fun isHandleProvided(): Boolean {
        return !TextUtils.isEmpty(handle)
    }
}

enum class ResponseStatus(val value: Int) {
    UNSENT(0),  //default
    ACCEPTED(1), REJECTED(2), IGNORED(3), UNSEEN(4), SENT(5);

}

enum class IntroDirection(val value: Int) {
    INCOMING(0), OUTGOING(1);

}