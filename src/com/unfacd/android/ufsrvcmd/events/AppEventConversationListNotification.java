package com.unfacd.android.ufsrvcmd.events;

import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

import org.thoughtcrime.securesms.database.loaders.ConversationListLoader;


public class AppEventConversationListNotification extends UfsrvCommandEvent
{

  ConversationListLoader.GroupMode groupMode;

  public AppEventConversationListNotification (ConversationListLoader.GroupMode groupMode)
  {
    this.groupMode=groupMode;
  }

  public ConversationListLoader.GroupMode getGroupMode ()
  {
    return groupMode;
  }
}
