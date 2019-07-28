package org.thoughtcrime.securesms.mms;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.util.Collections;
import java.util.List;

public class OutgoingSecureMediaMessage extends OutgoingMediaMessage {

  public OutgoingSecureMediaMessage(Recipient recipient, String body,
                                    List<Attachment> attachments,
                                    long sentTimeMillis,
                                    int distributionType,
                                    long expiresIn,
                                    long revealDuration,
                                    @Nullable QuoteModel quote,
                                    @NonNull List<Contact> contacts,
                                    @NonNull List<LinkPreview> previews,
                                    String ufsrvCommandWireBody)//
  {
    super(recipient, body, attachments, sentTimeMillis, -1, expiresIn, revealDuration, distributionType, quote, contacts, previews, Collections.emptyList(), Collections.emptyList(),
            ufsrvCommandWireBody); // uf
  }

  // constructor accepting ufsrvscommmand
  public OutgoingSecureMediaMessage(Recipient recipient, String body,
                                    List<Attachment> attachments,
                                    long sentTimeMillis,
                                    int distributionType,
                                    long expiresIn,
                                    long revealDuration,
                                    @Nullable QuoteModel quote,
                                    @NonNull List<Contact> contacts,
                                    @NonNull List<LinkPreview> previews,
                                    SignalServiceProtos.UfsrvCommandWire ufsrvCommand)//
  {
    super(recipient, body, attachments, sentTimeMillis, -1, expiresIn, revealDuration, distributionType, quote, contacts, previews, Collections.emptyList(), Collections.emptyList(),
            ufsrvCommand);
  }
  //

  public OutgoingSecureMediaMessage(OutgoingMediaMessage base) {
    super(base);
  }

  @Override
  public boolean isSecure() {
    return true;
  }
}

