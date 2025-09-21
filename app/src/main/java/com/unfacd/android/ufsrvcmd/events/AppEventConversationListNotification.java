package com.unfacd.android.ufsrvcmd.events;

import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;
import org.thoughtcrime.securesms.conversationlist.GroupMode;


public class AppEventConversationListNotification extends UfsrvCommandEvent
{

  GroupMode groupMode;

  public AppEventConversationListNotification (GroupMode groupMode)
  {
    this.groupMode = groupMode;
  }

  public GroupMode getGroupMode ()
  {
    return groupMode;
  }
}
