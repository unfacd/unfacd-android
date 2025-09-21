package com.unfacd.android.ufsrvcmd.events;


import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

import org.thoughtcrime.securesms.recipients.Recipient;

public class AppEventPrefUserReadReceiptShared extends UfsrvCommandEvent
{
  private final Boolean isEnabled;
  private final Recipient recipient;

  public AppEventPrefUserReadReceiptShared(Recipient recipient, Boolean isEnabled)
  {
    this.recipient = recipient;
    this.isEnabled  = isEnabled;
  }

  public Recipient getRecipient()
  {
    return this.recipient;
  }

  public Boolean getIsEnabled()
  {
    return isEnabled;
  }
}
