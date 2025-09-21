package org.thoughtcrime.securesms.mms;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.database.model.Mention;
import org.thoughtcrime.securesms.database.model.ParentStoryId;
import org.thoughtcrime.securesms.database.model.StoryType;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class OutgoingSecureMediaMessage extends OutgoingMediaMessage {

  public OutgoingSecureMediaMessage(Recipient recipient,
                                    String body,
                                    List<Attachment> attachments,
                                    long sentTimeMillis,
                                    int distributionType,
                                    long expiresIn,
                                    boolean viewOnce,
                                    @NonNull StoryType storyType,
                                    @Nullable ParentStoryId parentStoryId,
                                    boolean isStoryReaction,
                                    @Nullable QuoteModel quote,
                                    @NonNull List<Contact> contacts,
                                    @NonNull List<LinkPreview> previews,
                                    @NonNull List<Mention> mentions,
                                    String ufsrvCommandWireBody)//AA+
  {
    super(recipient, body, attachments, sentTimeMillis, -1, expiresIn, viewOnce, distributionType, storyType, parentStoryId, isStoryReaction, quote, contacts, previews, mentions, Collections.emptySet(), Collections.emptySet(),
            ufsrvCommandWireBody); //AA+ uf
  }

  //AA+ constructor accepting ufsrvscommmand
  public OutgoingSecureMediaMessage(Recipient recipient,
                                    String body,
                                    List<Attachment> attachments,
                                    long sentTimeMillis,
                                    int distributionType,
                                    long expiresIn,
                                    boolean viewOnce,
                                    @NonNull StoryType storyType,
                                    @Nullable ParentStoryId parentStoryId,
                                    boolean isStoryReaction,
                                    @Nullable QuoteModel quote,
                                    @NonNull List<Contact> contacts,
                                    @NonNull List<LinkPreview> previews,
                                    @NonNull List<Mention> mentions,
                                    UfsrvCommandWire ufsrvCommand)//AA+
  {
    super(recipient, body, attachments, sentTimeMillis, -1, expiresIn, viewOnce, distributionType, storyType, parentStoryId, isStoryReaction, quote, contacts, previews, mentions, Collections.emptySet(), Collections.emptySet(),
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

  @Override
  public @NonNull OutgoingMediaMessage withExpiry(long expiresIn) {
    return new OutgoingSecureMediaMessage(getRecipient(),
                                          getBody(),
                                          getAttachments(),
                                          getSentTimeMillis(),
                                          getDistributionType(),
                                          expiresIn,
                                          isViewOnce(),
                                          getStoryType(),
                                          getParentStoryId(),
                                          isStoryReaction(),
                                          getOutgoingQuote(),
                                          getSharedContacts(),
                                          getLinkPreviews(),
                                          getMentions(),
                                          (UfsrvCommandWire)null);
  }

  public @NonNull OutgoingSecureMediaMessage withSentTimestamp(long sentTimestamp) {
    return new OutgoingSecureMediaMessage(getRecipient(),
                                          getBody(),
                                          getAttachments(),
                                          sentTimestamp,
                                          getDistributionType(),
                                          getExpiresIn(),
                                          isViewOnce(),
                                          getStoryType(),
                                          getParentStoryId(),
                                          isStoryReaction(),
                                          getOutgoingQuote(),
                                          getSharedContacts(),
                                          getLinkPreviews(),
                                          getMentions(),
                                          (UfsrvCommandWire)null);
  }
}

