package com.unfacd.android.ufsrvcmd.events;


import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

import org.thoughtcrime.securesms.recipients.Recipient;

public class AppEventMessageEffectsCommand extends UfsrvCommandEvent
{
  private final int       commandArg;
  private final int       effect;
  private final String    things;
  private final Recipient recipientGroup;

  public AppEventMessageEffectsCommand (Recipient recipientGroup, int effect, String things, int commandArg)
  {
    this.effect         = effect;
    this.recipientGroup = recipientGroup;
    this.things         = things;
    this.commandArg     = commandArg;
  }

  public Recipient getRecipientGroup ()
  {
    return recipientGroup;
  }

  public String getThings ()
  {
    return things;
  }

  public int getEffect ()
  {
    return effect;
  }

  public int getCommandArg ()
  {
    return commandArg;
  }
}