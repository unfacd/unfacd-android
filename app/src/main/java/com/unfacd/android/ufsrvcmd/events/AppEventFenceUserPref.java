package com.unfacd.android.ufsrvcmd.events;


import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceUserPreference;

public class AppEventFenceUserPref extends UfsrvCommandEvent
{
  private final FenceUserPreference fenceUserPreference;
  public final CommandArgs         commandArgsServer;
  public final CommandArgs         commandArgsClient;

  public AppEventFenceUserPref (FenceUserPreference fenceUserPreference, CommandArgs commandArgsServer, CommandArgs commandArgsClient)
  {
    this.fenceUserPreference      = fenceUserPreference;
    this.commandArgsServer        = commandArgsServer;
    this.commandArgsClient        = commandArgsClient;
  }

  public FenceUserPreference getFenceUserPreference () {
    return this.fenceUserPreference;
  }
}