package org.thoughtcrime.securesms.stories.viewer.reply.group

import android.content.Context
import com.unfacd.android.ufsrvcmd.UfsrvCommand
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.ParentStoryId
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage
import org.thoughtcrime.securesms.sms.MessageSender
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire

/**
 * Stateless message sender for Story Group replies and reactions.
 */
object StoryGroupReplySender {

  fun sendReply(context: Context, storyId: Long, body: CharSequence, mentions: List<Mention>): Completable {
    return sendInternal(context, storyId, body, mentions, false)
  }

  fun sendReaction(context: Context, storyId: Long, emoji: String): Completable {
    return sendInternal(context, storyId, emoji, emptyList(), true)
  }

  private fun sendInternal(context: Context, storyId: Long, body: CharSequence, mentions: List<Mention>, isReaction: Boolean): Completable {
    return Completable.create {

      val message = SignalDatabase.mms.getMessageRecord(storyId)
      val recipient = SignalDatabase.threads.getRecipientForThreadId(message.threadId)!!

      MessageSender.send(
        context,
        OutgoingMediaMessage(
          recipient,
          body.toString(),
          emptyList(),
          System.currentTimeMillis(),
          0,
          0L,
          false,
          0,
          StoryType.NONE,
          ParentStoryId.GroupReply(message.id),
          isReaction,
          null,
          emptyList(),
          emptyList(),
          mentions,
          emptySet(),
          emptySet(),
          null as UfsrvCommandWire
        ),
        message.threadId,
        false,
        null,
        null,
        UfsrvCommand.TransportType.API_SERVICE,//AA+
        false
      )
        it.onComplete() //AA doen not have the enclosing {..} as per original as it was confusding the compiler

    }.subscribeOn(Schedulers.io())
  }
}