package org.thoughtcrime.securesms.jobs;


import com.annimon.stream.Stream;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.utils.UfsrvStateUtils;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.messages.GroupSendUtil;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.CancelationException;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage.Action;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.StateCommand;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;

public class TypingSendJob extends BaseJob {

  public static final String KEY = "TypingSendJob";

  private static final String TAG = Log.tag(TypingSendJob.class);

  private static final String KEY_THREAD_ID = "thread_id";
  private static final String KEY_TYPING    = "typing";

  private static final String KEY_FID    = "fid";//AA+

  private long    threadId;
  private boolean typing;

  private static List<Optional<UnidentifiedAccessPair>> emptyUnidentifiedAccess = new LinkedList<Optional<UnidentifiedAccessPair>>() {{add(Optional.empty());}}; //AA+

  public TypingSendJob(long threadId, boolean typing) {
    this(new Job.Parameters.Builder()
                 .setQueue(getQueue(threadId))
                 .setMaxAttempts(1)
                 .setLifespan(TimeUnit.SECONDS.toMillis(5))
                 .addConstraint(NetworkConstraint.KEY)
                 .setMemoryOnly(true)
                 .build(),
         threadId,
         typing);
  }

  public static String getQueue(long threadId) {
    return "TYPING_" + threadId;
  }

  private TypingSendJob(@NonNull Job.Parameters parameters, long threadId, boolean typing) {
    super(parameters);

    this.threadId = threadId;
    this.typing   = typing;
  }


  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_THREAD_ID, threadId)
            .putBoolean(KEY_TYPING, typing)
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws Exception {
    if (!TextSecurePreferences.isTypingIndicatorsEnabled(context)) {
      return;
    }

    long fid =  SignalDatabase.threads().getFidForThreadId(threadId);//AA+

    Log.d(TAG, "Sending typing " + (typing ? "started" : "stopped") + " for thread " + threadId);

    Recipient recipient = SignalDatabase.threads().getRecipientForThreadId(threadId);

    if (recipient == null) {
      Log.w(TAG, "Tried to send a typing indicator to a non-existent thread.");
      return;
    }

    if (recipient.isBlocked()) {
      Log.w(TAG, "Not sending typing indicators to blocked recipients. (AA+ method should return, but job continues)");
      return;
    }

    if (recipient.isSelf()) {
      Log.w(TAG, "Not sending typing indicators to self.");
      return;
    }

    List<Recipient>  recipients = Collections.singletonList(recipient);
    Optional<byte[]> groupId    = Optional.empty();

    if (recipient.isGroup()) {
      recipients = SignalDatabase.groups().getGroupMembers(recipient.requireGroupId(), GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);
      groupId    = Optional.of(recipient.requireGroupId().getDecodedId());
    }

    recipients = RecipientUtil.getEligibleForSending(Stream.of(recipients)
                                                             .map(Recipient::resolve)
                                                             .toList());

    SignalServiceTypingMessage typingMessage = new SignalServiceTypingMessage(typing ? Action.STARTED : Action.STOPPED, System.currentTimeMillis(), groupId);

    try {
      //AA+ fan out happens at server level. todo: Bit of waste to keep all the processing above as is
      recipients.clear();
      recipients.add(Recipient.self());

      UfsrvCommand ufsrvCommand = UfsrvStateUtils.buildTypingStateCommand(context, System.currentTimeMillis(), fid, typing ? StateCommand.StateTypes.STATE_TYPING_STARTED : StateCommand.StateTypes.STATE_TYPING_STOPPED);//AA+

      if (false && recipient.isPushV2Group()) {//AA+ false SenderKey not ported
        GroupSendUtil.sendTypingMessage(context, recipient.requireGroupId().requireV2(), recipients, typingMessage, this::isCanceled);//AA add ufsrvCommand
        GroupSendUtil.sendTypingMessage(context,
                                        recipient.getGroupId().map(GroupId::requireV2).orElse(null),
                                        recipients,
                                        typingMessage,
                                        this::isCanceled);
      } else {
        SignalServiceMessageSender             messageSender      = ApplicationDependencies.getSignalServiceMessageSender();
        List<SignalServiceAddress>             addresses          = RecipientUtil.toSignalServiceAddressesFromResolved(context, recipients);
        List<Optional<UnidentifiedAccessPair>> unidentifiedAccess = UnidentifiedAccessUtil.getAccessFor(context, recipients);

        if (addresses.isEmpty()) {
          Log.w(TAG, "No one to send typing indicators to");
          return;
        }

        if (isCanceled()) {
          Log.w(TAG, "Canceled before send!");
          return;
        }

        messageSender.sendTyping(addresses, unidentifiedAccess, typingMessage, this::isCanceled, ufsrvCommand);//AA+
      }
    } catch (CancelationException e) {
      Log.w(TAG, "Canceled during send!");
    }


  }

  @Override
  public void onFailure() {
  }

  @Override
  protected boolean onShouldRetry(Exception exception) {
    return false;
  }

  public static final class Factory implements Job.Factory<TypingSendJob> {
    @Override
    public @NonNull TypingSendJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new TypingSendJob(parameters, data.getLong(KEY_THREAD_ID), data.getBoolean(KEY_TYPING));
    }
  }
}