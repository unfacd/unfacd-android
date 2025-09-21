package com.unfacd.android.guardian;

import org.thoughtcrime.securesms.recipients.Recipient;

public class GuardianDescriptor
{
  final String challenge;

  final Recipient recipient;

  public GuardianDescriptor (Recipient recipient, String challenge)
  {
    this.recipient = recipient;
    this.challenge = challenge;
  }

  public String getChallenge ()
  {
    return challenge;
  }

  public Recipient getRecipient ()
  {
    return recipient;
  }
}
