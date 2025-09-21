package com.unfacd.android.ufsrvcmd.events;


import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;
import com.unfacd.android.guardian.GuardianDescriptor;

import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;

public class AppEventGuardianCommand extends UfsrvCommandEvent
{
  private final int         commandArg;
  private final int         command;
  private final GuardianDescriptor descriptor;
  private final Recipient recipientlocalGroup;

  public AppEventGuardianCommand (GuardianDescriptor descriptor, int command, int commandArg)
  {
    this.descriptor       = descriptor;
    this.command          = command;
    this.commandArg       = commandArg;
    this.recipientlocalGroup = null;
  }

  public AppEventGuardianCommand (Recipient recipient, GuardianDescriptor descriptor, int command, int commandArg)
  {
    this.descriptor       = descriptor;
    this.command          = command;
    this.commandArg       = commandArg;
    this.recipientlocalGroup = recipient;
  }

  public GuardianDescriptor getDescriptor ()
  {
    return descriptor;
  }

  public int getCommand() {
    return command;
  }

  public Recipient getRecipientlocalGroup ()
  {
    return recipientlocalGroup;
  }

  public boolean isCommandRequest() {
    return command == SignalServiceProtos.MessageCommand.CommandTypes.GUARDIAN_REQUEST_VALUE;
  }

  public boolean isCommandLink() {
    return command == SignalServiceProtos.MessageCommand.CommandTypes.GUARDIAN_LINK_VALUE;
  }

  public boolean isCommandUnLink() {
    return command == SignalServiceProtos.MessageCommand.CommandTypes.GUARDIAN_UNLINK_VALUE;
  }

  public boolean isCommandAccepted ()
  {
    return commandArg == CommandArgs.ACCEPTED.getNumber();
  }

  public boolean isCommandSynced ()
  {
    return commandArg == CommandArgs.SYNCED.getNumber();
  }
}