package com.unfacd.android.ufsrvcmd.events;


import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPreference;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;

public class AppEventUserPref extends UfsrvCommandEvent
{
  private final UserPreference userPreference;
  public final int             commandArgsServer;
  public final CommandArgs     commandArgsClient;
  private UserCommand.Errors   userCommandErrorArg;

  public AppEventUserPref(UserPreference userPreference, int commandArgsServer, CommandArgs commandArgsClient)
  {
    this.userPreference           = userPreference;
    this.commandArgsServer        = commandArgsServer;
    this.commandArgsClient        = commandArgsClient;
  }

  public AppEventUserPref(UserPreference userPreference, int commandArgsServer, CommandArgs commandArgsClient, UserCommand.Errors userCommandErrorArg)
  {
    this.userPreference           = userPreference;
    this.commandArgsServer        = commandArgsServer;
    this.commandArgsClient        = commandArgsClient;
    this.userCommandErrorArg      = userCommandErrorArg;
  }

  public SignalServiceProtos.UserPreference getUserPreference () {
    return this.userPreference;
  }


  public UserCommand.Errors getUserCommandErrorArg ()
  {
    return userCommandErrorArg;
  }


  public boolean hasErroArg()
  {
    return this.userCommandErrorArg != null;

  }
}