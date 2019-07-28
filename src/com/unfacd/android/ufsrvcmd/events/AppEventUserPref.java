package com.unfacd.android.ufsrvcmd.events;


import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPreference;

public class AppEventUserPref extends UfsrvCommandEvent
{
  private final UserPreference userPreference;
  public final int         commandArgsServer;
  public final CommandArgs         commandArgsClient;

  public AppEventUserPref (UserPreference userPreference, int commandArgsServer, CommandArgs commandArgsClient)
  {
    this.userPreference      = userPreference;
    this.commandArgsServer        = commandArgsServer;
    this.commandArgsClient        = commandArgsClient;
  }

  public SignalServiceProtos.UserPreference getUserPreference () {
    return this.userPreference;
  }
}