package com.unfacd.android.ufsrvcmd.events;

import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

import org.thoughtcrime.securesms.recipients.Recipient;

public class AppEventAvatarDownloaded extends UfsrvCommandEvent
{
  private final Recipient recipient;
  private String attachmentId;

  public AppEventAvatarDownloaded (Recipient recipient, String attachmentId)
  {
    this.recipient     = recipient;
    this.attachmentId  = attachmentId;
  }

  public Recipient getRecipient () {
    return recipient;
  }

  public String getAttachmentId ()
  {
    return attachmentId;
  }
}
