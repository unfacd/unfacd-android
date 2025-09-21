package org.thoughtcrime.securesms.mms;

import android.text.TextUtils;

import com.unfacd.android.ufsrvcmd.UfsrvCommand;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.documents.NetworkFailure;
import org.thoughtcrime.securesms.database.model.Mention;
import org.thoughtcrime.securesms.database.model.ParentStoryId;
import org.thoughtcrime.securesms.database.model.StoryType;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.util.Base64;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class OutgoingMediaMessage {

  private   final Recipient                 recipient; //AA for group encoded groupid (same concept as number for regular users)
  protected final String                    body; //AA serialised, b64-encoded GroupContext protobuf
  protected final List<Attachment>          attachments;
  private   final long                      sentTimeMillis;
  private   final int                       distributionType;
  private   final int                       subscriptionId;
  private   final long                      expiresIn;
  private   final boolean                   viewOnce;
  private   final QuoteModel                outgoingQuote;
  private   final StoryType                 storyType;
  private   final ParentStoryId             parentStoryId;
  private   final boolean                   isStoryReaction;

  private   final Set<NetworkFailure>      networkFailures       = new HashSet<>();
  private   final Set<IdentityKeyMismatch> identityKeyMismatches = new HashSet<>();
  private   final List<Contact>            contacts              = new LinkedList<>();
  private   final List<LinkPreview>        linkPreviews          = new LinkedList<>();
  private   final List<Mention>            mentions              = new LinkedList<>();

  //AA+
  protected String            ufsrvCommandBody                               = null; //base input from db. final serialised proto
  protected UfsrvCommandWire  ufsrvCommandWire = null;//this  base input
  //

  public OutgoingMediaMessage(Recipient recipient,
                              String message,
                              List<Attachment> attachments,
                              long sentTimeMillis,
                              int subscriptionId,
                              long expiresIn,
                              boolean viewOnce,
                              int distributionType,
                              @NonNull StoryType storyType,
                              @Nullable ParentStoryId parentStoryId,
                              boolean isStoryReaction,
                              @Nullable QuoteModel outgoingQuote,
                              @NonNull List<Contact> contacts,
                              @NonNull List<LinkPreview> linkPreviews,
                              @NonNull List<Mention> mentions,
                              @NonNull Set<NetworkFailure> networkFailures,
                              @NonNull Set<IdentityKeyMismatch> identityKeyMismatches,
                              String ufsrvCommandBody)//AA+ add usfrsv
  {
    this.recipient             = recipient; //for groups, internal group id
    this.body                  = message;//AA serialised GroupContext which contains actual group tel numbers
    this.sentTimeMillis        = sentTimeMillis;
    this.distributionType      = distributionType;
    this.attachments           = attachments;
    this.subscriptionId        = subscriptionId;
    this.expiresIn             = expiresIn;
    this.viewOnce              = viewOnce;
    this.outgoingQuote         = outgoingQuote;
    this.storyType             = storyType;
    this.parentStoryId         = parentStoryId;
    this.isStoryReaction       = isStoryReaction;



    this.contacts.addAll(contacts);
    this.linkPreviews.addAll(linkPreviews);
    this.mentions.addAll(mentions);
    this.networkFailures.addAll(networkFailures);
    this.identityKeyMismatches.addAll(identityKeyMismatches);

    //AA+
    this.ufsrvCommandBody = ufsrvCommandBody;
    inflateUfsrvCommand();//make sure we have a copy of the inflated type at hand this instance was bas64 encoded
    //
  }

  //AA+ for when we are passed a ufrsvcommand binary
  public OutgoingMediaMessage(Recipient recipient,
                              String message,
                              List<Attachment> attachments,
                              long sentTimeMillis,
                              int subscriptionId,
                              long expiresIn,
                              boolean viewOnce,
                              int distributionType,
                              @NonNull StoryType storyType,
                              @Nullable ParentStoryId parentStoryId,
                              boolean isStoryReaction,
                              @Nullable QuoteModel outgoingQuote,
                              @NonNull List<Contact> contacts,
                              @NonNull List<LinkPreview> linkPreviews,
                              @NonNull List<Mention> mentions,
                              @NonNull Set<NetworkFailure> networkFailures,
                              @NonNull Set<IdentityKeyMismatch> identityKeyMismatches,
                              UfsrvCommandWire ufsrvCommandWire)
  {
    this.recipient             = recipient;
    this.body                  = message;//AA serialised GroupContextwhic contains actual group tel numbers
    this.sentTimeMillis        = sentTimeMillis;
    this.distributionType      = distributionType;
    this.attachments           = attachments;
    this.subscriptionId        = subscriptionId;
    this.expiresIn             = expiresIn;
    this.viewOnce              = viewOnce;
    this.outgoingQuote         = outgoingQuote;
    this.storyType             = storyType;
    this.parentStoryId         = parentStoryId;
    this.isStoryReaction       = isStoryReaction;


    this.contacts.addAll(contacts);
    this.linkPreviews.addAll(linkPreviews);
    this.mentions.addAll(mentions);
    this.networkFailures.addAll(networkFailures);
    this.identityKeyMismatches.addAll(identityKeyMismatches);

    this.ufsrvCommandWire      = ufsrvCommandWire;
    serialiseUfsrvCommand();
  }
  //

  public OutgoingMediaMessage(Recipient recipient,
                              SlideDeck slideDeck,
                              String message,
                              long sentTimeMillis,
                              int subscriptionId,
                              long expiresIn,
                              boolean viewOnce,
                              int distributionType,
                              @NonNull StoryType storyType,
                              @Nullable ParentStoryId parentStoryId,
                              boolean isStoryReaction,
                              @Nullable QuoteModel outgoingQuote,
                              @NonNull List<Contact> contacts,
                              @NonNull List<LinkPreview> linkPreviews,
                              @NonNull List<Mention> mentions)
  {
    this(recipient,
         buildMessage(slideDeck, message),
         slideDeck.asAttachments(),
         sentTimeMillis,
         subscriptionId,
         expiresIn,
         viewOnce,
         distributionType,
         storyType,
         parentStoryId,
         isStoryReaction,
         outgoingQuote,
         contacts,
         linkPreviews,
         mentions,
         new HashSet<>(),
         new HashSet<>(),
         (UfsrvCommandWire)null);//AA+ todo: chase down the methods and include ufrsvcommand type
  }

  public OutgoingMediaMessage(OutgoingMediaMessage that)
  {
    this.recipient           = that.getRecipient();
    this.body                = that.body;
    this.distributionType    = that.distributionType;
    this.attachments         = that.attachments;
    this.sentTimeMillis      = that.sentTimeMillis;
    this.subscriptionId      = that.subscriptionId;
    this.expiresIn           = that.expiresIn;
    this.viewOnce            = that.viewOnce;
    this.outgoingQuote       = that.outgoingQuote;
    this.storyType           = that.storyType;
    this.parentStoryId       = that.parentStoryId;
    this.isStoryReaction     = that.isStoryReaction;

    this.identityKeyMismatches.addAll(that.identityKeyMismatches);
    this.networkFailures.addAll(that.networkFailures);
    this.contacts.addAll(that.contacts);
    this.linkPreviews.addAll(that.linkPreviews);
    this.mentions.addAll(that.mentions);

    //AA+
    this.ufsrvCommandWire = that.ufsrvCommandWire;
    this.ufsrvCommandBody = that.ufsrvCommandBody;
    inflateUfsrvCommand();//make sure we have a copy of the inflated type at handif the instance was text based
    serialiseUfsrvCommand();//ensure we have a copy of the serialised tpe if the instance was object based
    //
  }

  public @NonNull OutgoingMediaMessage withExpiry(long expiresIn) {
    return new OutgoingMediaMessage(
            getRecipient(),
            body,
            attachments,
            sentTimeMillis,
            subscriptionId,
            expiresIn,
            viewOnce,
            distributionType,
            storyType,
            parentStoryId,
            isStoryReaction,
            outgoingQuote,
            contacts,
            linkPreviews,
            mentions,
            networkFailures,
            identityKeyMismatches,
            (UfsrvCommandWire)null//AA+
    );
  }

  public Recipient getRecipient() {
    return recipient;
  }

  public boolean isViewOnce() {
    return viewOnce;
  }

  public @NonNull StoryType getStoryType() {
    return storyType;
  }

  public @Nullable ParentStoryId getParentStoryId() {
    return parentStoryId;
  }

  public boolean isStoryReaction() {
    return isStoryReaction;
  }

  public @Nullable QuoteModel getOutgoingQuote() {
    return outgoingQuote;
  }

  public String getBody() {
    return body;
  }

  public List<Attachment> getAttachments() {
    return attachments;
  }

  public int getDistributionType() {
    return distributionType;
  }

  public boolean isSecure() {
    return false;
  }

  public boolean isGroup() {
    return false;
  }

  public boolean isExpirationUpdate() {
    return false;
  }

  public long getSentTimeMillis() {
    return sentTimeMillis;
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  //AA+
  public boolean isUfsrvCommand ()
  {
    return (this.ufsrvCommandBody!=null || this.ufsrvCommandWire!=null);
  }

  public String getUfsrvCommandBody ()
  {
    return ufsrvCommandBody;
  }


  public UfsrvCommand getUfsrvCommand ()
  {
    //at the moment we churn out copies, we could churn out references, but must consider concurrency issues
    if (this.ufsrvCommandWire != null) {
      return (new UfsrvCommand(ufsrvCommandWire, true, true));
    }

    return null;
  }

  public SignalServiceProtos.UfsrvCommandWire getUfsrvCommandWire ()
  {
    return this.ufsrvCommandWire;
  }

  //in instances where we passed a serialised copy, ensure we hve a inflated instance at hand
  private void inflateUfsrvCommand ()
  {
    if (this.ufsrvCommandWire == null && this.ufsrvCommandBody != null) {
      try {
        this.ufsrvCommandWire = SignalServiceProtos.UfsrvCommandWire.parseFrom(Base64.decode(this.ufsrvCommandBody));
      } catch (IOException ex) {
        Log.w("OutgoingMediaMessage", "Could not inflate UfsrvCommand: "+ex.getMessage());
      }
    }
    //else we have an inflated instance already
  }

  //chiefly for when we've been constructedby direct instance
  private void serialiseUfsrvCommand ()
  {
    if (this.ufsrvCommandWire != null && this.ufsrvCommandBody == null) {
      this.ufsrvCommandBody = Base64.encodeBytes(this.ufsrvCommandWire.toByteArray());
    }
  }
  //

  public @NonNull List<Contact> getSharedContacts() {
    return contacts;
  }

  public @NonNull List<LinkPreview> getLinkPreviews() {
    return linkPreviews;
  }

  public @NonNull List<Mention> getMentions() {
    return mentions;
  }

  public @NonNull Set<NetworkFailure> getNetworkFailures() {
    return networkFailures;
  }

  public @NonNull Set<IdentityKeyMismatch> getIdentityKeyMismatches() {
    return identityKeyMismatches;
  }

  private static String buildMessage(SlideDeck slideDeck, String message) {
    Log.d("OutgoingMedaMessage", "buildMessage: Building SlideDeck mesage...");
    if (!TextUtils.isEmpty(message) && !TextUtils.isEmpty(slideDeck.getBody())) {
      return slideDeck.getBody() + "\n\n" + message;
    } else if (!TextUtils.isEmpty(message)) {
      return message;
    } else {
      return slideDeck.getBody();
    }
  }
}

