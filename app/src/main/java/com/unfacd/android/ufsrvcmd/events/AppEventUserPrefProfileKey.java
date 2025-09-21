package com.unfacd.android.ufsrvcmd.events;


import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPreference;

public class AppEventUserPrefProfileKey extends UfsrvCommandEvent
{
  private final UserPreference     userPreference;
  private final Recipient recipient;
  public final CommandArgs         commandArgsServer;
  public final CommandArgs         commandArgsClient;

  public AppEventUserPrefProfileKey (UserPreference userPreference, Recipient recipient, CommandArgs commandArgsServer, CommandArgs commandArgsClient)
  {
    this.userPreference     = userPreference;
    this.recipient          = recipient;
    this.commandArgsServer  = commandArgsServer;
    this.commandArgsClient  = commandArgsClient;
  }

  public UserPreference getUserPreference () {
    return this.userPreference;
  }

  public Recipient getRecipient () {
    return recipient;
  }
}