package org.thoughtcrime.securesms.mms;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Base64;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

public class OutgoingGroupMediaMessage extends OutgoingSecureMediaMessage {

  private final GroupContext group;

  // for when groupContext is already serialised, eg coming out of the db
  public OutgoingGroupMediaMessage(@NonNull Recipient recipient,
                                   @NonNull String encodedGroupContext,
                                   @NonNull List<Attachment> avatar,
                                   long sentTimeMillis,
                                   long expiresIn,
                                   long revealDuration,
                                   @Nullable QuoteModel quote,
                                   @NonNull List<Contact> contacts,
                                   @NonNull List<LinkPreview> previews,
                                   @Nullable String ufsrvCommandWireBody)// not finalised yet
          throws IOException
  {
    super(recipient, encodedGroupContext, avatar, sentTimeMillis,
            ThreadDatabase.DistributionTypes.CONVERSATION, expiresIn, revealDuration, quote, contacts, previews,
            ufsrvCommandWireBody);

    this.group = GroupContext.parseFrom(Base64.decode(encodedGroupContext));

    //
    //
  }

  // for serialising binary group context
  public OutgoingGroupMediaMessage(@NonNull Recipient recipient,
                                   @NonNull GroupContext group,
                                   @Nullable final Attachment avatar,
                                   long sentTimeMillis,
                                   long expireIn,
                                   long revealDuration,
                                   @Nullable QuoteModel quote,
                                   @NonNull List<Contact> contacts,
                                   @NonNull List<LinkPreview> previews,
                                   SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire)//
  {
    // for groups recicoients is the enocded groupid
    super(recipient, Base64.encodeBytes(group.toByteArray()),
            new LinkedList<Attachment>() {{if (avatar != null) add(avatar);}},
            sentTimeMillis,
            ThreadDatabase.DistributionTypes.CONVERSATION, expireIn, revealDuration, quote, contacts, previews,
            Base64.encodeBytes(ufsrvCommandWire.toByteArray()));//

    this.group = group;
  }

  @Override
  public boolean isGroup() {
    return true;
  }

  public boolean isGroupUpdate() {
    return group.getType().getNumber() == GroupContext.Type.UPDATE_VALUE;
  }

  public boolean isGroupQuit() {
    return group.getType().getNumber() == GroupContext.Type.QUIT_VALUE;
  }

  public GroupContext getGroupContext() {
    return group;
  }
}

