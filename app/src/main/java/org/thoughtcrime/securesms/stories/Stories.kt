package org.thoughtcrime.securesms.stories

import androidx.annotation.WorkerThread
import androidx.fragment.app.FragmentManager
import com.unfacd.android.R
import io.reactivex.rxjava3.core.Completable
import org.signal.glide.Log
import org.thoughtcrime.securesms.contacts.HeaderAction
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mediasend.v2.stories.ChooseStoryTypeBottomSheet
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.BottomSheetUtil
import org.thoughtcrime.securesms.util.FeatureFlags

object Stories {
  @JvmStatic
  fun isFeatureAvailable(): Boolean {
    return FeatureFlags.stories() && Recipient.self().storiesCapability == Recipient.Capability.SUPPORTED
  }

  @JvmStatic
  fun isFeatureEnabled(): Boolean {
    return isFeatureAvailable() && !SignalStore.storyValues().isFeatureDisabled
  }

  fun getHeaderAction(fragmentManager: FragmentManager): HeaderAction {
    return HeaderAction(
      R.string.ContactsCursorLoader_new_story,
      R.drawable.ic_plus_20
    ) {
      ChooseStoryTypeBottomSheet().show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }
  }

  @WorkerThread
  fun sendTextStories(messages: List<OutgoingSecureMediaMessage>): Completable {
    Log.w("Stories","WARNING THIS FUNCTION WAS MODIFIED AND MAY NOT WORK PROPERLY... emitter.onComplete without enclosing {}")
    //AA check https://github.com/signalapp/Signal-Android/commit/7f4a12c1793e537ce03820139ced7289cc69b07b#diff-5896476476c8c4f739b8c1c90a99615deb168af0bd324350857c2fb3d7937c0e
    return Completable.create { emitter ->
      MessageSender.sendMediaBroadcast(ApplicationDependencies.getApplication(), messages, listOf(), listOf())
      emitter.onComplete()
    }
  }

  @JvmStatic
  fun getRecipientsToSendTo(messageId: Long, sentTimestamp: Long, allowsReplies: Boolean): List<Recipient> {
    val recipientIds: List<RecipientId> = SignalDatabase.storySends.getRecipientsToSendTo(messageId, sentTimestamp, allowsReplies)

    return RecipientUtil.getEligibleForSending(recipientIds.map(Recipient::resolved))
  }
}