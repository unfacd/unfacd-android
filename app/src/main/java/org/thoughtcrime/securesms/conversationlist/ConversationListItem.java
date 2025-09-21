  /**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.conversationlist;

  import android.content.Context;
  import android.graphics.Typeface;
  import android.text.Spannable;
  import android.text.SpannableString;
  import android.text.SpannableStringBuilder;
  import android.text.TextUtils;
  import android.text.style.StyleSpan;
  import android.util.AttributeSet;
  import android.view.View;
  import android.widget.ImageView;
  import android.widget.TextView;

  import com.bumptech.glide.load.resource.bitmap.CenterCrop;
  import com.makeramen.roundedimageview.RoundedDrawable;
  import com.unfacd.android.R;
  import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
  import com.unfacd.android.ufsrvuid.UfsrvUid;
  import com.unfacd.android.ui.components.AvatarWithIndicators;
  import com.unfacd.android.utils.UfsrvFenceUtils;
  import com.unfacd.android.utils.UfsrvUserUtils;

  import org.signal.core.util.DimensionUnit;
  import org.signal.core.util.logging.Log;
  import org.thoughtcrime.securesms.BindableConversationListItem;
  import org.thoughtcrime.securesms.OverlayTransformation;
  import org.thoughtcrime.securesms.Unbindable;
  import org.thoughtcrime.securesms.badges.BadgeImageView;
  import org.thoughtcrime.securesms.components.AlertView;
  import org.thoughtcrime.securesms.components.AvatarImageView;
  import org.thoughtcrime.securesms.components.DeliveryStatusView;
  import org.thoughtcrime.securesms.components.FromTextView;
  import org.thoughtcrime.securesms.components.TypingIndicatorView;
  import org.thoughtcrime.securesms.components.emoji.EmojiStrings;
  import org.thoughtcrime.securesms.conversationlist.model.ConversationSet;
  import org.thoughtcrime.securesms.database.GroupDatabase;
  import org.thoughtcrime.securesms.database.MmsSmsColumns;
  import org.thoughtcrime.securesms.database.SignalDatabase;
  import org.thoughtcrime.securesms.database.SmsDatabase;
  import org.thoughtcrime.securesms.database.ThreadDatabase;
  import org.thoughtcrime.securesms.database.model.LiveUpdateMessage;
  import org.thoughtcrime.securesms.database.model.MessageRecord;
  import org.thoughtcrime.securesms.database.model.ThreadRecord;
  import org.thoughtcrime.securesms.database.model.UpdateDescription;
  import org.thoughtcrime.securesms.glide.GlideLiveDataTarget;
  import org.thoughtcrime.securesms.groups.GroupV1MessageProcessor;
  import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader;
  import org.thoughtcrime.securesms.mms.GlideRequests;
  import org.thoughtcrime.securesms.recipients.LiveRecipient;
  import org.thoughtcrime.securesms.recipients.Recipient;
  import org.thoughtcrime.securesms.recipients.RecipientForeverObserver;
  import org.thoughtcrime.securesms.recipients.RecipientId;
  import org.thoughtcrime.securesms.search.MessageResult;
  import org.thoughtcrime.securesms.util.DateUtils;
  import org.thoughtcrime.securesms.util.Debouncer;
  import org.thoughtcrime.securesms.util.ExpirationUtil;
  import org.thoughtcrime.securesms.util.MediaUtil;
  import org.thoughtcrime.securesms.util.SearchUtil;
  import org.thoughtcrime.securesms.util.SpanUtil;
  import org.thoughtcrime.securesms.util.TextSecurePreferences;
  import org.thoughtcrime.securesms.util.livedata.LiveDataUtil;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceUserPreference;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPreference;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;

  import java.util.Locale;
  import java.util.Set;

  import androidx.annotation.ColorInt;
  import androidx.annotation.DrawableRes;
  import androidx.annotation.NonNull;
  import androidx.annotation.Nullable;
  import androidx.annotation.Px;
  import androidx.constraintlayout.widget.ConstraintLayout;
  import androidx.core.content.ContextCompat;
  import androidx.lifecycle.LiveData;
  import androidx.lifecycle.Observer;
  import androidx.lifecycle.Transformations;

  import static org.thoughtcrime.securesms.database.model.LiveUpdateMessage.recipientToStringAsync;

public final class ConversationListItem extends ConstraintLayout
        implements RecipientForeverObserver,
        BindableConversationListItem,
        Unbindable,
        Observer<SpannableString>
{
  @SuppressWarnings("unused")
  private final static String TAG = Log.tag(ConversationListItem.class);

  private final static Typeface  BOLD_TYPEFACE  = Typeface.create("sans-serif-medium", Typeface.NORMAL);
  private final static Typeface  LIGHT_TYPEFACE = Typeface.create("sans-serif", Typeface.NORMAL);

  private static final int MAX_SNIPPET_LENGTH = 500;

  private Set<Long>           typingThreads;
  private LiveRecipient       recipient;
  private long                threadId;
  private GlideRequests       glideRequests;
  private TextView            subjectView;
  private TypingIndicatorView typingView;
  private FromTextView        fromView;
  private TextView            dateView;
  private TextView            archivedView;
  private DeliveryStatusView  deliveryStatusIndicator;
  private AlertView           alertView;
  private TextView            unreadIndicator;
  private long                lastSeen;
  private ThreadRecord        thread;
  private boolean             batchMode;
  private Locale              locale;
  private String              highlightSubstring;
  private BadgeImageView      badge;
  private View                checkContainer;
  private View                uncheckedView;
  private View                checkedView;
  private int                 thumbSize;
  private GlideLiveDataTarget thumbTarget;

  private int             unreadCount;
  private AvatarImageView contactPhotoImage;

  private LiveData<SpannableString> displayBody;

  public ConversationListItem(Context context) {
    this(context, null);
  }

  public ConversationListItem(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    this.subjectView             = findViewById(R.id.conversation_list_item_summary);
    this.typingView              = findViewById(R.id.conversation_list_item_typing_indicator);
    this.fromView                = findViewById(R.id.conversation_list_item_name);
    this.dateView                = findViewById(R.id.conversation_list_item_date);
    this.deliveryStatusIndicator = findViewById(R.id.conversation_list_item_status);
    this.alertView               = findViewById(R.id.conversation_list_item_alert);
    this.contactPhotoImage       = findViewById(R.id.conversation_list_item_avatar);
    this.archivedView            = findViewById(R.id.conversation_list_item_archived);
    this.unreadIndicator         = findViewById(R.id.conversation_list_item_unread_indicator);
    this.badge                   = findViewById(R.id.conversation_list_item_badge);
    this.checkContainer          = findViewById(R.id.conversation_list_item_check_container);
    this.uncheckedView           = findViewById(R.id.conversation_list_item_unchecked);
    this.checkedView             = findViewById(R.id.conversation_list_item_checked);
    this.thumbSize               = (int) DimensionUnit.SP.toPixels(20f);
    this.thumbTarget             = new GlideLiveDataTarget(thumbSize, thumbSize);

    getLayoutTransition().setDuration(150);
  }


  @Override
  public void bind(@NonNull ThreadRecord thread,
                   @NonNull GlideRequests glideRequests,
                   @NonNull Locale locale,
                   @NonNull Set<Long> typingThreads,
                   @NonNull ConversationSet selectedConversations)
  {
    bindThread(thread, glideRequests, locale, typingThreads, selectedConversations, null);
  }

  public void bindThread(@NonNull ThreadRecord thread,
                         @NonNull GlideRequests glideRequests,
                         @NonNull Locale locale,
                         @NonNull Set<Long> typingThreads,
                         @NonNull ConversationSet selectedConversations,
                         @Nullable String highlightSubstring)
  {
    observeRecipient(thread.getRecipient().live());
    observeDisplayBody(null);

    this.threadId           = thread.getThreadId();
    this.glideRequests      = glideRequests;
    this.unreadCount        = thread.getUnreadCount();
    this.lastSeen           = thread.getLastSeen();
    this.thread             = thread;
    this.locale             = locale;
    this.highlightSubstring = highlightSubstring;

    if (highlightSubstring != null) {
      String name = recipient.get().isSelf() ? getContext().getString(R.string.note_to_self) : recipient.get().getDisplayName(getContext());

      this.fromView.setText(recipient.get(), SearchUtil.getHighlightedSpan(locale, SpanUtil::getMediumBoldSpan, name, highlightSubstring, SearchUtil.MATCH_ALL), true, null);
    } else {
      this.fromView.setText(recipient.get(), false);
    }

    this.typingThreads = typingThreads;
    updateTypingIndicator(typingThreads);

    LiveData<SpannableString> displayBody = getThreadDisplayBody(getContext(), thread, glideRequests, thumbSize, thumbTarget);
    setSubjectViewText(displayBody.getValue());
    observeDisplayBody(displayBody);

    if (thread.getDate() > 0) {
      CharSequence date = DateUtils.getBriefRelativeTimeSpanString(getContext(), locale, thread.getDate());
      dateView.setText(date);
      dateView.setTypeface(thread.isRead() ? LIGHT_TYPEFACE : BOLD_TYPEFACE);
      dateView.setTextColor(thread.isRead() ? ContextCompat.getColor(getContext(), R.color.signal_text_secondary)
                                            : ContextCompat.getColor(getContext(), R.color.signal_text_primary));
    }

    if (thread.isArchived()) {
      this.archivedView.setVisibility(View.VISIBLE);
    } else {
      this.archivedView.setVisibility(View.GONE);
    }

    setStatusIcons(thread);
    setSelectedConversations(selectedConversations);
    setBadgeFromRecipient(recipient.get());
    setUnreadIndicator(thread);
    this.contactPhotoImage.setAvatar(glideRequests, recipient.get(), !batchMode);

    //AA+
    if (recipient.get().isGroup()) {
      GroupDatabase.GroupRecord groupRecord = SignalDatabase.groups()
              .getGroup(recipient.getId()).orElse(null);

      if (groupRecord != null) {
        if (groupRecord.getOwnerUserId() == TextSecurePreferences.getUserId(getContext()))
          this.contactPhotoImage.setIndicator(AvatarWithIndicators.EnumAvatarIndicators.TOP_RIGHT);
        if (groupRecord.getType() == GroupDatabase.GroupType.GEO.getValue())
          this.contactPhotoImage.setIndicator(AvatarWithIndicators.EnumAvatarIndicators.TOP_LEFT);

      }
    }
    //
  }

  private void setBadgeFromRecipient(Recipient recipient) {
    if (!recipient.isSelf()) {
      badge.setBadgeFromRecipient(recipient);
      badge.setClickable(false);
    } else {
      badge.setBadge(null);
    }
  }

  public void bindContact(@NonNull  Recipient     contact,
                          @NonNull  GlideRequests glideRequests,
                          @NonNull  Locale        locale,
                          @Nullable String        highlightSubstring)
  {
    observeRecipient(contact.live());
    observeDisplayBody(null);
    setSubjectViewText(null);

    this.glideRequests      = glideRequests;
    this.locale             = locale;
    this.highlightSubstring = highlightSubstring;

    fromView.setText(contact, SearchUtil.getHighlightedSpan(locale, SpanUtil::getMediumBoldSpan, new SpannableString(contact.getDisplayName(getContext())), highlightSubstring, SearchUtil.MATCH_ALL), true, null);
    setSubjectViewText(SearchUtil.getHighlightedSpan(locale, SpanUtil::getBoldSpan, contact.getE164().orElse(""), highlightSubstring, SearchUtil.MATCH_ALL));
    dateView.setText("");
    archivedView.setVisibility(GONE);
    unreadIndicator.setVisibility(GONE);
    deliveryStatusIndicator.setNone();
    alertView.setNone();

    setSelectedConversations(new ConversationSet());
    setBadgeFromRecipient(recipient.get());
    contactPhotoImage.setAvatar(glideRequests, recipient.get(), !batchMode);

    //AA+
    if (recipient.get().isGroup()) {
      GroupDatabase.GroupRecord groupRecord =SignalDatabase.groups().getGroup(recipient.getId()).orElse(null);

      if (groupRecord != null) {
        if (groupRecord.getOwnerUserId() == TextSecurePreferences.getUserId(getContext()))
          this.contactPhotoImage.setIndicator(AvatarWithIndicators.EnumAvatarIndicators.TOP_RIGHT);
        if (groupRecord.getType() == GroupDatabase.GroupType.GEO.getValue())
          this.contactPhotoImage.setIndicator(AvatarWithIndicators.EnumAvatarIndicators.TOP_LEFT);

      }
    }
    //
  }

  public void bindMessage(@NonNull  MessageResult messageResult,
                          @NonNull  GlideRequests glideRequests,
                          @NonNull  Locale        locale,
                          @Nullable String        highlightSubstring)
  {
    observeRecipient(messageResult.getConversationRecipient().live());
    observeDisplayBody(null);
    setSubjectViewText(null);

    this.glideRequests      = glideRequests;
    this.locale             = locale;
    this.highlightSubstring = highlightSubstring;

    fromView.setText(recipient.get(), false);
    setSubjectViewText(SearchUtil.getHighlightedSpan(locale, SpanUtil::getBoldSpan, messageResult.getBodySnippet(), highlightSubstring, SearchUtil.MATCH_ALL));
    dateView.setText(DateUtils.getBriefRelativeTimeSpanString(getContext(), locale, messageResult.getReceivedTimestampMs()));
    archivedView.setVisibility(GONE);
    unreadIndicator.setVisibility(GONE);
    deliveryStatusIndicator.setNone();
    alertView.setNone();

    setSelectedConversations(new ConversationSet());
    setBadgeFromRecipient(recipient.get());
    contactPhotoImage.setAvatar(glideRequests, recipient.get(), !batchMode);

    //AA+
    if (recipient.get().isGroup()) {
      GroupDatabase.GroupRecord groupRecord = SignalDatabase.groups().getGroup(recipient.getId()).orElse(null);

      if (groupRecord != null) {
        if (groupRecord.getOwnerUserId() == TextSecurePreferences.getUserId(getContext()))
          this.contactPhotoImage.setIndicator(AvatarWithIndicators.EnumAvatarIndicators.TOP_RIGHT);
        if (groupRecord.getType() == GroupDatabase.GroupType.GEO.getValue())
          this.contactPhotoImage.setIndicator(AvatarWithIndicators.EnumAvatarIndicators.TOP_LEFT);

      }
    }
    //
  }

  public void unbind() {
    if (this.recipient != null) {
      observeRecipient(null);
      setSelectedConversations(new ConversationSet());
      contactPhotoImage.setAvatar(glideRequests, null, !batchMode);
    }

    observeDisplayBody(null);
  }

  @Override
  public void setSelectedConversations(@NonNull ConversationSet conversations) {
    this.batchMode = !conversations.isEmpty();

    boolean selected = batchMode && conversations.containsThreadId(thread.getThreadId());
    setSelected(selected);

    if (recipient != null) {
      contactPhotoImage.setAvatar(glideRequests, recipient.get(), !batchMode);
    }

    if (batchMode && selected) {
      checkContainer.setVisibility(VISIBLE);
      uncheckedView.setVisibility(GONE);
      checkedView.setVisibility(VISIBLE);
    } else if (batchMode) {
      checkContainer.setVisibility(VISIBLE);
      uncheckedView.setVisibility(VISIBLE);
      checkedView.setVisibility(GONE);
    } else {
      checkContainer.setVisibility(GONE);
      uncheckedView.setVisibility(GONE);
      checkedView.setVisibility(GONE);
    }
  }

  @Override
  public void updateTypingIndicator(@NonNull Set<Long> typingThreads) {
    if (typingThreads.contains(threadId)) {
      this.subjectView.setVisibility(INVISIBLE);

      this.typingView.setVisibility(VISIBLE);
      this.typingView.startAnimation();
    } else {
      this.typingView.setVisibility(GONE);
      this.typingView.stopAnimation();

      this.subjectView.setVisibility(VISIBLE);
    }
  }

  public Recipient getRecipient() {
    return recipient.get();
  }

  public long getThreadId() {
    return threadId;
  }

  public @NonNull ThreadRecord getThread() {
    return thread;
  }

  public int getUnreadCount() {
    return unreadCount;
  }

  public long getLastSeen() {
    return lastSeen;
  }

  private void observeRecipient(@Nullable LiveRecipient newRecipient) {
    if (this.recipient != null) {
      this.recipient.removeForeverObserver(this);
    }

    this.recipient = newRecipient;

    if (this.recipient != null) {
      this.recipient.observeForever(this);
    }
  }

  private void observeDisplayBody(@Nullable LiveData<SpannableString> displayBody) {
    if (displayBody == null && glideRequests != null) {
      glideRequests.clear(thumbTarget);
    }

    if (this.displayBody != null) {
      this.displayBody.removeObserver(this);
    }

    this.displayBody = displayBody;

    if (this.displayBody != null) {
      this.displayBody.observeForever(this);
    }
  }

  private void setSubjectViewText(@Nullable CharSequence text) {
    if (text == null) {
      subjectView.setText(null);
    } else {

      subjectView.setText(text);
      subjectView.setVisibility(VISIBLE);
    }
  }

  private void setStatusIcons(ThreadRecord thread) {
    if (MmsSmsColumns.Types.isBadDecryptType(thread.getType())) {
      deliveryStatusIndicator.setNone();
      alertView.setFailed();
    } else if (!thread.isOutgoing()         ||
            thread.isOutgoingAudioCall() ||
            thread.isOutgoingVideoCall() ||
            thread.isVerificationStatusChange())
    {
      deliveryStatusIndicator.setNone();
      alertView.setNone();
    } else if (thread.isFailed()) {
      deliveryStatusIndicator.setNone();
      alertView.setFailed();
    } else if (thread.isPendingInsecureSmsFallback()) {
      deliveryStatusIndicator.setNone();
      alertView.setPendingApproval();
    } else {
      alertView.setNone();

      if (thread.getExtra() != null && thread.getExtra().isRemoteDelete()) {
        if (thread.isPending()) {
          deliveryStatusIndicator.setPending();
        } else {
          deliveryStatusIndicator.setNone();
        }
      } else {
        if (thread.isPending()) {
          deliveryStatusIndicator.setPending();
        } else if (thread.isRemoteRead()) {
          deliveryStatusIndicator.setRead();
        } else if (thread.isDelivered()) {
          deliveryStatusIndicator.setDelivered();
        } else {
          deliveryStatusIndicator.setSent();
        }
      }
    }
  }

  private void setUnreadIndicator(ThreadRecord thread) {
    if ((thread.isOutgoing() && !thread.isForcedUnread()) || thread.isRead()) {
      unreadIndicator.setVisibility(View.GONE);
      return;
    }

    String count = unreadCount > 100 ? String.valueOf(unreadCount) : "+99";
    unreadIndicator.setText(unreadCount > 0 ? String.valueOf(unreadCount) : " ");
    unreadIndicator.setVisibility(View.VISIBLE);
  }

  //from old ThreadRecord
  //AA+
  boolean isThreadInvitationToJoin()
  {
    GroupDatabase groupDatabase =SignalDatabase.groups();
    FenceRecord fenceRecord = UfsrvFenceUtils.getTargetFence(thread.getUfsrvCommand());
    if (fenceRecord!=null) {
      GroupDatabase.GroupRecord groupRecord = groupDatabase.getGroupRecordByFid(UfsrvFenceUtils.getTargetFence(thread.getUfsrvCommand()).getFid());
      if (groupRecord != null && groupRecord.getMode() == GroupDatabase.GROUP_MODE_INVITATION)
        return true;
    }

    return false;
  }
  //

  private static @NonNull LiveData<SpannableString> getThreadDisplayBody(@NonNull Context context,
                                                                         @NonNull ThreadRecord thread,
                                                                         @NonNull GlideRequests glideRequests,
                                                                         @Px int thumbSize,
                                                                         @NonNull GlideLiveDataTarget thumbTarget)
  {
    int defaultTint = ContextCompat.getColor(context, R.color.signal_text_secondary);

    //AA+
    UfsrvCommandWire ufsrvCommandWire = thread.getUfsrvCommand();

    if (SmsDatabase.Types.isGroupUpdate(thread.getType()) && thread.isOutgoing()) {//isThreadInvitationToJoin()) {
      return describeOutgoingUpdate(context, ufsrvCommandWire, thread);
    }  else if (SmsDatabase.Types.isProfileLog(thread.getType())) {
      return describeProfileLog(context, ufsrvCommandWire, defaultTint);
    } else if ( SmsDatabase.Types.isGroupProfileLog(thread.getType())) {
      return describeGroupProfileLog(context, ufsrvCommandWire, defaultTint);
    } else if (SmsDatabase.Types.isGuardianLog(thread.getType())) {
      return describeGuardianLog(context, thread, defaultTint);
    } else if (SmsDatabase.Types.isReportedMessageLog(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.MessageDisplayHelper_message_flagged_inappropriate), defaultTint);
    } else if (!thread.isMessageRequestAccepted()) {
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_message_request), defaultTint);
    } else if (SmsDatabase.Types.isGroupUpdate(thread.getType())) {
      return describeIncomingUpdate(context, ufsrvCommandWire, thread, defaultTint);
    } else if (SmsDatabase.Types.isGroupQuit(thread.getType()) && thread.isOutgoing()) {//AA+ second conditional
      return emphasisAdded(context, context.getString(R.string.requesting_to_leave_group), R.drawable.ic_update_group_leave_16, defaultTint);//AA-
    } else if (SmsDatabase.Types.isGroupQuit(thread.getType())) {
      return describeIncomingLeave(context, ufsrvCommandWire, defaultTint);
    } else if (SmsDatabase.Types.isKeyExchangeType(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.ConversationListItem_key_exchange_message), defaultTint);
    } else if (SmsDatabase.Types.isChatSessionRefresh(thread.getType())) {
      UpdateDescription description = UpdateDescription.staticDescription(context.getString(R.string.ThreadRecord_chat_session_refreshed), R.drawable.ic_refresh_16);
      return emphasisAdded(context, description, defaultTint);
    } else if (SmsDatabase.Types.isNoRemoteSessionType(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.MessageDisplayHelper_message_encrypted_for_non_existing_session), defaultTint);
    } else if (SmsDatabase.Types.isEndSessionType(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_secure_session_reset), defaultTint);
    } else if (MmsSmsColumns.Types.isLegacyType(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.MessageRecord_message_encrypted_with_a_legacy_protocol_version_that_is_no_longer_supported), defaultTint);
    } else if (MmsSmsColumns.Types.isDraftMessageType(thread.getType())) {
      String draftText = context.getString(R.string.ThreadRecord_draft);
      return emphasisAdded(context, draftText + " " + thread.getBody(), defaultTint);
    } else if (SmsDatabase.Types.isOutgoingAudioCall(thread.getType()) || SmsDatabase.Types.isOutgoingVideoCall(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_called), defaultTint);
    } else if (SmsDatabase.Types.isIncomingAudioCall(thread.getType()) || SmsDatabase.Types.isIncomingVideoCall(thread.getType())) {
      return emphasisAdded(context, context.getString(com.unfacd.android.R.string.ThreadRecord_called_you), defaultTint);
    } else if (SmsDatabase.Types.isMissedAudioCall(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_missed_audio_call), defaultTint);
    } else if (SmsDatabase.Types.isMissedVideoCall(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_missed_video_call), defaultTint);
    } else if (MmsSmsColumns.Types.isGroupCall(thread.getType())) {
      return emphasisAdded(context, MessageRecord.getGroupCallUpdateDescription(context, thread.getBody(), false), defaultTint);
    } else if (SmsDatabase.Types.isJoinedType(thread.getType())) {
      return emphasisAdded(recipientToStringAsync(thread.getRecipient().getId(), r -> new SpannableString(context.getString(R.string.ThreadRecord_s_is_on_unfacd, r.getDisplayName(context)))));
    } else if (SmsDatabase.Types.isExpirationTimerUpdate(thread.getType())) {
      int seconds = (int)(thread.getExpiresIn() / 1000);
      if (seconds <= 0) {
        return emphasisAdded(context, context.getString(R.string.ThreadRecord_disappearing_messages_disabled), defaultTint);
      }
      String time = ExpirationUtil.getExpirationDisplayValue(context, seconds);
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_disappearing_message_time_updated_to_s, time), defaultTint);
    } else if (SmsDatabase.Types.isIdentityUpdate(thread.getType())) {
      return emphasisAdded(recipientToStringAsync(thread.getRecipient().getId(), r -> {
        if (r.isGroup()) {
          return new SpannableString(context.getString(R.string.ThreadRecord_safety_number_changed));
        } else {
          return new SpannableString(context.getString(R.string.ThreadRecord_your_safety_number_with_s_has_changed, r.getDisplayName(context)));
        }
      }));
    } else if (SmsDatabase.Types.isIdentityVerified(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_you_marked_verified), defaultTint);
    } else if (SmsDatabase.Types.isIdentityDefault(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_you_marked_unverified), defaultTint);
    } else if (SmsDatabase.Types.isUnsupportedMessageType(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_message_could_not_be_processed), defaultTint);
    } else if (SmsDatabase.Types.isProfileChange(thread.getType())) {
      return emphasisAdded(context, "", defaultTint);
    } else if (SmsDatabase.Types.isChangeNumber(thread.getType()) || SmsDatabase.Types.isBoostRequest(thread.getType())) {
      return emphasisAdded(context, "", defaultTint);
    } else if (MmsSmsColumns.Types.isBadDecryptType(thread.getType())) {
      return emphasisAdded(context, context.getString(R.string.ThreadRecord_delivery_issue), defaultTint);
    } else {
      ThreadDatabase.Extra extra = thread.getExtra();
      if (extra != null && extra.isViewOnce()) {
        return emphasisAdded(context, getViewOnceDescription(context, thread.getContentType()), defaultTint);
      } else if (extra != null && extra.isRemoteDelete()) {
        return emphasisAdded(context, context.getString(thread.isOutgoing() ? R.string.ThreadRecord_you_deleted_this_message : R.string.ThreadRecord_this_message_was_deleted), defaultTint);
      } else {
        String                    body      = removeNewlines(thread.getBody());
        LiveData<SpannableString> finalBody = Transformations.map(createFinalBodyWithMediaIcon(context, body, thread, glideRequests, thumbSize, thumbTarget), updatedBody -> {
          if (thread.getRecipient().isGroup()) {
            RecipientId groupMessageSender = thread.getGroupMessageSender();
            if (!groupMessageSender.isUnknown()) {
              return createGroupMessageUpdateString(context, updatedBody, Recipient.resolved(groupMessageSender));
            }
          }

          return new SpannableString(updatedBody);
        });

        return whileLoadingShow(body, finalBody);
      }
    }
  }

  private static LiveData<CharSequence> createFinalBodyWithMediaIcon(@NonNull Context context,
                                                                     @NonNull String body,
                                                                     @NonNull ThreadRecord thread,
                                                                     @NonNull GlideRequests glideRequests,
                                                                     @Px int thumbSize,
                                                                     @NonNull GlideLiveDataTarget thumbTarget)
  {
    if (thread.getSnippetUri() == null) {
      return LiveDataUtil.just(body);
    }

    final String bodyWithoutMediaPrefix;

    if (body.startsWith(EmojiStrings.GIF)) {
      bodyWithoutMediaPrefix = body.replaceFirst(EmojiStrings.GIF, "");
    } else if (body.startsWith(EmojiStrings.VIDEO)) {
      bodyWithoutMediaPrefix = body.replaceFirst(EmojiStrings.VIDEO, "");
    } else if (body.startsWith(EmojiStrings.PHOTO)) {
      bodyWithoutMediaPrefix = body.replaceFirst(EmojiStrings.PHOTO, "");
    } else if (thread.getExtra() != null && thread.getExtra().getStickerEmoji() != null && body.startsWith(thread.getExtra().getStickerEmoji())) {
      bodyWithoutMediaPrefix = body.replaceFirst(thread.getExtra().getStickerEmoji(), "");
    } else {
      return LiveDataUtil.just(body);
    }

    glideRequests.asBitmap()
            .load(new DecryptableStreamUriLoader.DecryptableUri(thread.getSnippetUri()))
            .override(thumbSize, thumbSize)
            .transform(
                    new OverlayTransformation(ContextCompat.getColor(context, R.color.transparent_black_08)),
                    new CenterCrop()
            )
            .into(thumbTarget);

    return Transformations.map(thumbTarget.getLiveData(), bitmap -> {
      if (bitmap == null) {
        return body;
      }

      RoundedDrawable drawable = RoundedDrawable.fromBitmap(bitmap);
      drawable.setBounds(0, 0, thumbSize, thumbSize);
      drawable.setCornerRadius(DimensionUnit.DP.toPixels(4));
      drawable.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

      CharSequence thumbnailSpan = SpanUtil.buildCenteredImageSpan(drawable);

      return new SpannableStringBuilder()
              .append(thumbnailSpan)
              .append(bodyWithoutMediaPrefix);
    });
  }

  private static SpannableString createGroupMessageUpdateString(@NonNull Context context,
                                                                @NonNull CharSequence body,
                                                                @NonNull Recipient recipient)
  {
    String sender = (recipient.isSelf() ? context.getString(R.string.MessageRecord_you)
                                        : recipient.getShortDisplayName(context)) + ": ";

    SpannableStringBuilder builder = new SpannableStringBuilder(sender).append(body);
    builder.setSpan(SpanUtil.getBoldSpan(),
                    0,
                    sender.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

    return new SpannableString(builder);
  }

  /** After a short delay, if the main data hasn't shown yet, then a loading message is displayed. */
  private static @NonNull LiveData<SpannableString> whileLoadingShow(@NonNull String loading, @NonNull LiveData<SpannableString> string) {
    return LiveDataUtil.until(string, LiveDataUtil.delay(250, new SpannableString(loading)));
  }

  private static @NonNull String removeNewlines(@Nullable String text) {
    if (text == null) {
      return "";
    }

    if (text.indexOf('\n') >= 0) {
      return text.replaceAll("\n", " ");
    } else {
      return text;
    }
  }

  private static @NonNull LiveData<SpannableString> emphasisAdded (@NonNull Context context, @NonNull String string, @ColorInt int defaultTint) {
    return emphasisAdded(context, UpdateDescription.staticDescription(string, 0), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> emphasisAdded(@NonNull Context context, @NonNull String string, @DrawableRes int iconResource, @ColorInt int defaultTint) {
    return emphasisAdded(context, UpdateDescription.staticDescription(string, iconResource), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> emphasisAdded (@NonNull Context context, @NonNull UpdateDescription description, @ColorInt int defaultTint) {
    return emphasisAdded(LiveUpdateMessage.fromMessageDescription(context, description, defaultTint, false));
  }

  private static @NonNull LiveData<SpannableString> emphasisAdded(@NonNull LiveData<SpannableString> description) {
    return Transformations.map(description, sequence -> {
      SpannableString spannable = new SpannableString(sequence);
      spannable.setSpan(new StyleSpan(Typeface.ITALIC),
                        0,
                        sequence.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      return spannable;
    });
  }

  private static String getViewOnceDescription(@NonNull Context context, @Nullable String contentType) {
    if (MediaUtil.isViewOnceType(contentType)) {
      return context.getString(R.string.ThreadRecord_view_once_media);
    } else if (MediaUtil.isVideoType(contentType)) {
      return context.getString(R.string.ThreadRecord_view_once_video);
    } else {
      return context.getString(R.string.ThreadRecord_view_once_photo);
    }
  }

  private static @NonNull LiveData<SpannableString> describeOutgoingUpdate(Context context, UfsrvCommandWire ufsrvCommand, @NonNull ThreadRecord thread)
  {
    int defaultTint = thread.isRead() ? ContextCompat.getColor(context, R.color.signal_text_secondary)
                                      : ContextCompat.getColor(context, R.color.signal_text_primary);

    if (ufsrvCommand == null) {
      return emphasisAdded(context, String.format(Locale.getDefault(), "UFSRV command was not provided"), defaultTint);
    }

    FenceCommand  fenceCommand  = ufsrvCommand.getFenceCommand();
    int           commandArgs   = fenceCommand.getHeader().getArgs();

    switch (fenceCommand.getHeader().getCommand())
    {
      case FenceCommand.CommandTypes.JOIN_VALUE:
        if (fenceCommand.getFencesCount() > 0 && fenceCommand.getFences(0).getFid() == 0)
          return emphasisAdded(context, context.getString(R.string.waiting_for_group_confirmation), defaultTint);
        else
          return describeOutgoingUpdateJoin(context, ufsrvCommand, defaultTint);

      case FenceCommand.CommandTypes.LINKJOIN_VALUE:
        Recipient recipientOriginator = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getOriginator());

        switch (commandArgs)
        {
          case CommandArgs.ADDED_VALUE:
            return emphasisAdded(context,  new SpannableString(context.getString(R.string.message_record_outgoing_Sending_request_to_join_via_link)).toString(), defaultTint);

          case CommandArgs.ACCEPTED_VALUE:
          return emphasisAdded(context,  new SpannableString(context.getString(R.string.message_record_outgoing_group_admin_authorised_join_request, recipientOriginator.getDisplayName())).toString(), defaultTint);

          case CommandArgs.REJECTED_VALUE:
          return emphasisAdded(context,  new SpannableString(context.getString(R.string.message_record_outgoing_group_admin_rejected_join_request, recipientOriginator.getDisplayName())).toString(), defaultTint);

        }

      case FenceCommand.CommandTypes.FNAME_VALUE:
        return emphasisAdded(context, context.getString(R.string.requesting_group_name), defaultTint);

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        return emphasisAdded(context, context.getString(R.string.updatings_group_avatar), defaultTint);

      case FenceCommand.CommandTypes.INVITE_VALUE:
        return emphasisAdded(context, context.getString(R.string.sending_grou_invitation), defaultTint);

      case FenceCommand.CommandTypes.STATE_VALUE:
        return emphasisAdded(context, context.getString(R.string.requesting_group_update), defaultTint);

      case FenceCommand.CommandTypes.PERMISSION_VALUE:
        return emphasisAdded(context, context.getString(R.string.updating_group_permission_thread), defaultTint);

      case FenceCommand.CommandTypes.MAXMEMBERS_VALUE:
        return emphasisAdded(context, context.getString(R.string.updating_group_maxmembers_thread), defaultTint);

      case FenceCommand.CommandTypes.INVITE_REJECTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.sending_invitation_rejection), defaultTint);

      case FenceCommand.CommandTypes.INVITE_DELETED_VALUE:
        return emphasisAdded(context, context.getString(R.string.sending_invitation_removal), defaultTint);

    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown outgoing group event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  public static @NonNull LiveData<SpannableString> describeProfileLog(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    UserCommand userCommand     = ufsrvCommand.getUserCommand();
    UserPreference  userPref    = userCommand.getPrefs(0);
    int             commandArgs = userCommand.getHeader().getArgs();

    // pref changes to other users
    Recipient recipient = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);

    if (recipient != null) {
      switch (userPref.getPrefId())
      {
        case NICKNAME:
          return handleUserProfileLogExtended(context, userCommand, "Nickname", defaultTint);
        case USERAVATAR:
          return handleUserProfileLogExtended(context, userCommand, "Avatar", defaultTint);
        default:
          return handleProfileLogExtended(context, userCommand, defaultTint);
      }
    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown User Profile type (%d)", userPref.getPrefId()), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> handleUserProfileLogExtended(Context context, UserCommand userCommand, String profileDescriptorName, int defaultTint)
  {
    int commandArg = userCommand.getHeader().getArgs();
    Recipient recipient = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);

    if (commandArg == CommandArgs.UPDATED_VALUE) {
      return emphasisAdded(context, context.getString(R.string.x_updated_for, profileDescriptorName, recipient.getDisplayName()), defaultTint);
    } else if (commandArg == CommandArgs.DELETED_VALUE) {
      return emphasisAdded(context, context.getString(R.string.x_deleted_their, recipient.getDisplayName(), profileDescriptorName), defaultTint);
    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Uknown User Profile update (%d)", commandArg), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> handleProfileLogExtended(Context context, SignalServiceProtos.UserCommand userCommand, int defaultTint)
  {
    SignalServiceProtos.UserPreference userPref  = userCommand.getPrefs(0);
    int commandArgs = userCommand.getHeader().getArgs();

    switch (userPref.getPrefId()) {
      case PROFILE:
        return describeProfileLogExtended(context, userCommand, "profile", defaultTint);
      case NETSTATE:
        return describeProfileLogExtended(context, userCommand, "presence information", defaultTint);
      case READ_RECEIPT:
        return describeProfileLogExtended(context, userCommand, "read receipt", defaultTint);
      case BLOCKING:
        return describeProfileLogBlock(context, userCommand, defaultTint);
      case CONTACTS:
        return describeProfileLogContact(context, userCommand, defaultTint);
      case ACTIVITY_STATE:
        return describeProfileLogExtended(context, userCommand, "typing indicator information", defaultTint);
      case BLOCKED_FENCE:
        return describeProfileLogBlockedFence(context, userCommand, defaultTint);
      default:
        return emphasisAdded(context, "Unknown UserPreference", defaultTint);
    }

  }

  private static @NonNull LiveData<SpannableString> describeProfileLogExtended(Context context, UserCommand userCommand, String profileDescriptorName, int defaultTint)
  {
    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      //server accepted a previous request to add/remove a user to this client's sharelist. Doesn't mean the other user accepted that. (see ADDED below for that)
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();

      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return emphasisAdded(context, context.getString(R.string.you_are_now_sharing_x_with_y, profileDescriptorName, recipientSharingWith.getDisplayName()), defaultTint);
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return emphasisAdded(context, context.getString(R.string.you_are_no_longer_sharing_x_with_y, profileDescriptorName, recipientSharingWith.getDisplayName()), defaultTint);
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();
      return emphasisAdded(context, context.getString(R.string.unfacd_rejected_x_with_y, profileDescriptorName, recipientSharingWith.getDisplayName()), defaultTint);
    } else if (UfsrvUserUtils.isCommandAdded(userCommand)) {
      //the originator is allowing/shared their presence with this client
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return emphasisAdded(context, context.getString(R.string.x_is_now_sharing_their_with_you, recipientOriginator.getDisplayName(), profileDescriptorName), defaultTint);
    } else if (UfsrvUserUtils.isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return emphasisAdded(context, context.getString(R.string.x_is_no_longer_sharing_their_with_you, recipientOriginator.getDisplayName(), profileDescriptorName), defaultTint);
    }

    return emphasisAdded(context, String.format("Unknown UserPreference: (args:'%d', args_client:'%d')", userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsClient().getNumber()), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeProfileLogBlock(Context context, UserCommand userCommand, int defaultTint)
  {
    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient  recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();

      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return emphasisAdded(context, context.getString(R.string.x_is_now_on_your_blocked_list, recipientSharingWith.getDisplayName()), defaultTint);
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return emphasisAdded(context, context.getString(R.string.x_was_removed_from_your_blocked_list, recipientSharingWith.getDisplayName()), defaultTint);
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      return emphasisAdded(context, context.getString(R.string.unfacd_rejected_your_block_request), defaultTint);
    } else if (UfsrvUserUtils.isCommandAdded(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return emphasisAdded(context, context.getString(R.string.x_was_added_to_their__blocked_list, recipientOriginator.getDisplayName()), defaultTint);
    } else if (UfsrvUserUtils.isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return emphasisAdded(context, context.getString(R.string.x_was_removed_from_their__blocked_list, recipientOriginator.getDisplayName()), defaultTint);
    }

    return emphasisAdded(context, String.format("Unknown block setting: (args:'%d', args_client:'%d')", userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsClient().getNumber()), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeProfileLogBlockedFence(Context context, UserCommand userCommand, int defaultTint)
  {
    FenceRecord fenceRecord = userCommand.getFencesBlocked(0);
    Recipient recipient = Recipient.resolved(RecipientUfsrvId.from(fenceRecord.getFid()));

    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return emphasisAdded(context, context.getString(R.string.successfully_blocked_this_group), defaultTint);
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return  emphasisAdded(context, context.getString(R.string.successfully_unblocked_x, recipient.getDisplayName(context)), defaultTint);
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      return emphasisAdded(context, context.getString(R.string.blocking_unblocking_request_unsuccessful), defaultTint);
    } else if (UfsrvUserUtils.isCommandAdded(userCommand)) {
      if (UfsrvUserUtils.isCommandClientEmpty(userCommand)) {
        return emphasisAdded(context, context.getString(R.string.requesting_blocking), defaultTint);
      } else { //this branch unsupported at this stage, as ufsrv doesn't share blocked fence updates by other users
        Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      }
    } else if (UfsrvUserUtils.isCommandDeleted(userCommand)) {
      if (UfsrvUserUtils.isCommandClientEmpty(userCommand)) {
        return emphasisAdded(context, context.getString(R.string.requesting_blocking), defaultTint);
      } else { //this branch unsupported at this stage, as ufsrv doesn't share blocked fence updates by other users
        Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      }
    }

    return emphasisAdded(context, String.format("Unknown Fence Block setting: (fid:'%d', args:'%d', args_client:'%d')", fenceRecord.getFid(), userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsClient().getNumber()), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeProfileLogContact(Context context, UserCommand userCommand, int defaultTint)
  {
    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient  recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();

      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return emphasisAdded(context, context.getString(R.string.x_is_now_allowed_to_share_your_contact_info, recipientSharingWith.getDisplayName()), defaultTint);
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return emphasisAdded(context, context.getString(R.string.x_is_no_longer_allowed_to_share_your_contact_info, recipientSharingWith.getDisplayName()), defaultTint);
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      return emphasisAdded(context, context.getString(R.string.unfacd_rejected_your_contact_request), defaultTint);
    } else if (UfsrvUserUtils.isCommandAdded(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return emphasisAdded(context, context.getString(R.string.x_is_now_allowing_u_to_share_their_contact, recipientOriginator.getDisplayName()), defaultTint);
    } else if (UfsrvUserUtils.isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return emphasisAdded(context, context.getString(R.string.you_are_no_longer_allowed_to_share_x_contact, recipientOriginator.getDisplayName()), defaultTint);
    }

    return emphasisAdded(context, String.format("Unknown block setting: (args:'%d', args_client:'%d')", userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsClient().getNumber()), defaultTint);
  }

  //AA+
  private static @NonNull LiveData<SpannableString> describeGroupProfileLog(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    UserCommand         userCommand = ufsrvCommand.getUserCommand();
    FenceUserPreference userPref    = userCommand.getFencePrefs(0);

    if (UfsrvUserUtils.isCommandUpdated(userCommand)) {
      Recipient recipient = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      if (recipient != null) {
        switch (userPref.getPrefId())
        {
          case PROFILE_SHARING:
            return emphasisAdded(context, context.getString(R.string.profile_sharing_with_group_update), defaultTint);

          case STICKY_GEOGROUP:
            return emphasisAdded(context, context.getString(R.string.avatar_updated_for, recipient.getDisplayName()), defaultTint);

          default:
            break;
        }
      }
    }

    return emphasisAdded(context, "Unknown Fence UserPreference", defaultTint);

  }

  private static @NonNull LiveData<SpannableString> describeGuardianLog(Context context, @NonNull ThreadRecord thread, int defaultTint)
  {
    int commandType = thread.getUfsrvCommandType();
    int commandArg  = thread.getUfsrvCommandArg();
    switch (commandType) {
      case MessageCommand.CommandTypes.GUARDIAN_REQUEST_VALUE:
        if (commandArg == CommandArgs.ACCEPTED_VALUE) {
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_guardian_request_accepted), defaultTint);
        } else if (commandArg == CommandArgs.SYNCED_VALUE) {
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_guardian_request_received), defaultTint);
        } else if (commandArg == CommandArgs.REJECTED_VALUE){
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_guardian_request_rejected), defaultTint);
        }
        break;

      case MessageCommand.CommandTypes.GUARDIAN_LINK_VALUE:
        if (commandArg == CommandArgs.ACCEPTED_VALUE) {
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_guardian_link_accepted), defaultTint);
        } else if (commandArg == CommandArgs.SYNCED_VALUE) {
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_guardian_link_received), defaultTint);
        } else if (commandArg == CommandArgs.REJECTED_VALUE){
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_guardian_request_rejected), defaultTint);
        }
        break;

      case MessageCommand.CommandTypes.GUARDIAN_UNLINK_VALUE:
        if (commandArg == CommandArgs.ACCEPTED_VALUE) {
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_guardian_request_initiated), defaultTint);
        } else if (commandArg == CommandArgs.SYNCED_VALUE) {
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_guardian_request_initiated), defaultTint);
        } else if (commandArg == CommandArgs.REJECTED_VALUE){
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_guardian_request_rejected), defaultTint);
        }
        break;
    }

    return emphasisAdded(context, context.getString(R.string.MessageRecord_guardian_request_initiated), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeOutgoingUpdateJoin(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand fenceCommand  = ufsrvCommand.getFenceCommand();
    int          commandArgs   = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.INVITED_VALUE:
        return emphasisAdded(context, context.getString(R.string.accepting_invitation), defaultTint);

      case CommandArgs.DENIED_VALUE:
        return emphasisAdded(context, context.getString(R.string.declining_request), defaultTint);

      case CommandArgs.UNCHANGED_VALUE:
        return emphasisAdded(context, context.getString(R.string.MessageRecord_uf_rejoined_group), defaultTint);

      case CommandArgs.LINK_BASED_VALUE:
        return emphasisAdded(context, context.getString(R.string.ThreadRecord_Joining_via_group_link), defaultTint);
    }

    if (fenceCommand.getHeader().getCommand() == 0 && commandArgs == 0) return emphasisAdded(context, "", defaultTint); //this happens when no messages in the thread
    else return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown outgoing group join event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeInviteRejected(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand fenceCommand  = ufsrvCommand.getFenceCommand();
    int          commandArgs   = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.ACCEPTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.invitation_rejection_processed), defaultTint);

      case CommandArgs.SYNCED_VALUE:
        if (fenceCommand.getFences(0).getInvitedMembersCount() > 0) {
          Recipient recipient = Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();
          return emphasisAdded(context, context.getString(R.string.ThreadRecord_invitation_rejected_by_x, recipient.getDisplayName()), defaultTint);
        }

    }

    if (fenceCommand.getHeader().getCommand() == 0 && commandArgs == 0) return emphasisAdded(context, "", defaultTint); //this happens when no messages in the thread
    else return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown outgoing group invite rejected event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeInviteDeleted(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand fenceCommand  = ufsrvCommand.getFenceCommand();
    int          commandArgs   = fenceCommand.getHeader().getArgs();
    Recipient    recipientUninvited = Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();

    switch (commandArgs)
    {
      case CommandArgs.ACCEPTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.successfully_withdrawn_invitation_for_x, recipientUninvited.getDisplayName(context)), defaultTint);

      case CommandArgs.SYNCED_VALUE:
        if (recipientUninvited.equals(Recipient.self())) {
          return emphasisAdded(context, context.getString(R.string.you_have_been_removed_from_invite_list), defaultTint);

        } else {
          return emphasisAdded(context, context.getString(R.string.x_is_no_longer_on_invite_list, recipientUninvited.getDisplayName()), defaultTint);
        }

    }

    if (fenceCommand.getHeader().getCommand() == 0 && commandArgs == 0) return emphasisAdded(context, "", defaultTint); //this happens when no messages in the thread
    else return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown outgoing group invite deleted event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeIncomingUpdate(Context context, UfsrvCommandWire ufsrvCommand, @NonNull ThreadRecord thread, int defaultTint)
  {
    if (ufsrvCommand == null) {
      Log.e(TAG, String.format("ThreadRecord: EMPTY UFSRV COMMAND"));
      return emphasisAdded(context, "Error: Undefined content", defaultTint);
    }

    FenceCommand  fenceCommand  = ufsrvCommand.getFenceCommand();
    int           commandArgs   = fenceCommand.getHeader().getArgs();

    switch (fenceCommand.getHeader().getCommand())
    {
      case FenceCommand.CommandTypes.JOIN_VALUE:
        return describeIncomingUpdateJoin(context, ufsrvCommand, defaultTint);

      case FenceCommand.CommandTypes.LINKJOIN_VALUE:
        return describeIncomingUpdateLinkJoin(context, ufsrvCommand, defaultTint);

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        return emphasisAdded(context, context.getString(R.string.group_avatar_changed_thread), defaultTint);

      case FenceCommand.CommandTypes.FNAME_VALUE:
        if (fenceCommand.getHeader().getArgsError() == FenceCommand.Errors.NONE_VALUE) {
          return emphasisAdded(context, context.getString(R.string.group_name_changed_thread), defaultTint);
        } else return emphasisAdded(context, context.getString(R.string.error_changing_group_name_thread), defaultTint);

      case FenceCommand.CommandTypes.INVITE_VALUE:
        return (describeIncomingUpdateInvite(context, ufsrvCommand, defaultTint));

      case FenceCommand.CommandTypes.INVITE_REJECTED_VALUE:
        return describeInviteRejected(context, ufsrvCommand, defaultTint);

      case FenceCommand.CommandTypes.INVITE_DELETED_VALUE:
        return describeInviteDeleted(context, ufsrvCommand, defaultTint);

      case FenceCommand.CommandTypes.STATE_VALUE:
        return (describeIncomingUpdateState(context, ufsrvCommand, defaultTint));

      case FenceCommand.CommandTypes.PERMISSION_VALUE:
        return (describeIncomingFencePermission(context, ufsrvCommand, defaultTint));

      case FenceCommand.CommandTypes.MAXMEMBERS_VALUE:
        return describeIncomingUpdateMaxMembers(context, ufsrvCommand, defaultTint);

      case FenceCommand.CommandTypes.DELIVERY_MODE_VALUE:
        return describeIncomingUpdateDeliveryMode(context, ufsrvCommand, defaultTint);
    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown incoming group event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeIncomingUpdateJoin(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.CREATED_VALUE:
        return emphasisAdded(context, context.getString(R.string.MessageRecord_uf_created_group), defaultTint);

      case CommandArgs.UNCHANGED_VALUE:
        return emphasisAdded(context, context.getString(R.string.MessageRecord_uf_rejoined_group), defaultTint);

      case CommandArgs.ACCEPTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.you_have_joined_the_group), defaultTint);

      case CommandArgs.GEO_BASED_VALUE:
        return emphasisAdded(context, context.getString(R.string.you_have_joined_a_geo_group), defaultTint);

      case CommandArgs.INVITED_VALUE:
      {
        String sourceUser = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
        return emphasisAdded(context, context.getString(R.string.user_invited_you_to_join_thread, Recipient.live(sourceUser).get().getDisplayName()), defaultTint);
      }
      case CommandArgs.INVITED_GEO_VALUE:
        return emphasisAdded(context, context.getString(R.string.you_received_geogroup_invitation_thread), defaultTint);

      case CommandArgs.ACCEPTED_INVITE_VALUE:
        return emphasisAdded(context, context.getString(R.string.jined_by_prior_invitation), defaultTint);

      case CommandArgs.LINK_BASED_VALUE:
        return emphasisAdded(context, context.getString(R.string.ThreadRecord_Joined_via_group_link), defaultTint);

      //for this user the originator value is unset. This is distinct message from the other join confirmation type, as this carries full group configuration data for the newly joining user
      case CommandArgs.SYNCED_VALUE:
        String sourceUser         = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
        if (!TextUtils.isEmpty(sourceUser) && !sourceUser.equals(UfsrvUid.UndefinedUfsrvUid))
          return emphasisAdded(context, context.getString(R.string.user_joined_the_group, Recipient.live(sourceUser).get().getDisplayName()), defaultTint);
        else emphasisAdded(context, context.getString(R.string.the_group_is_fully_loaded), defaultTint);

    }

    if (fenceCommand.getHeader().getCommand() == 0 && commandArgs == 0) return emphasisAdded(context, "", defaultTint); //this happens when no messages in the thread
    else return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown incoming group event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeIncomingUpdateLinkJoin(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();
    boolean hasOriginator = false;
    Recipient recipientOriginator;
    Recipient recipientAuthoriser;
    SpannableStringBuilder s;

    switch (commandArgs)
    {
      case CommandArgs.ADDED_VALUE:
        recipientOriginator = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getOriginator());
        if (!recipientOriginator.equals(Recipient.self())) {
          s = new SpannableStringBuilder(context.getString(R.string.message_record_incoming_Request_s_is_joining_via_join_link_request, recipientOriginator.getDisplayName(context)));
        } else {
          s = new SpannableStringBuilder().append(context.getString(R.string.message_record_incoming_Request_to_join_successfully_sent));
        }
        return emphasisAdded(context, s.toString(), defaultTint);

      case CommandArgs.ACCEPTED_VALUE:
        hasOriginator = fenceCommand.hasOriginator();
        if (hasOriginator) {
          recipientOriginator = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getOriginator());
          s = new SpannableStringBuilder(context.getString(R.string.message_record_incoming_finalised_link_join_request_for_s, recipientOriginator.getDisplayName(context)));
        } else {
          recipientAuthoriser = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getAuthoriser());
          s = new SpannableStringBuilder().append(context.getString(R.string.message_record_incoming_group_admin_authorised_join_request));
        }
        return emphasisAdded(context, s.toString(), defaultTint);

      case CommandArgs.REJECTED_VALUE:
        hasOriginator = fenceCommand.hasOriginator();
        if (hasOriginator) {
          recipientOriginator = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getOriginator());
          s = new SpannableStringBuilder(context.getString(R.string.message_record_incoming_finalised_rejected_link_join_request_for_s, recipientOriginator.getDisplayName(context)));
        } else {
          recipientAuthoriser = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getAuthoriser());
          s = new SpannableStringBuilder().append(context.getString(R.string.message_record_incoming_group_admin_rejected_join_request));
        }
        return emphasisAdded(context, s.toString(), defaultTint);
    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown incoming linkjoin group event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeIncomingUpdateInvite(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case SignalServiceProtos.CommandArgs.ADDED_VALUE:
        if (fenceCommand.getFences(0).getInvitedMembersCount() > 0) {
          Recipient recipient =  Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();
          return emphasisAdded(context, context.getString(R.string.x_newly_invited, recipient.getDisplayName()), defaultTint);
        }
        break;
      case CommandArgs.UNCHANGED_VALUE:
        if (fenceCommand.getFences(0).getInvitedMembersCount() > 0) {
          Recipient recipient =  Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();
          return emphasisAdded(context, context.getString(R.string.x_is_already_inviyted, recipient.getDisplayName()), defaultTint);
        }
        break;

    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown incoming  group INVITE event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeIncomingFencePermission(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand  fenceCommand  = ufsrvCommand.getFenceCommand();
    int           commandArgs   = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.ADDED_VALUE:
        //todo: check permission user's list for self
        return emphasisAdded(context, context.getString(R.string.group_permissions_updated_thread), defaultTint);

      case CommandArgs.DELETED_VALUE:
        //todo: check permission user's list for self
        return emphasisAdded(context, context.getString(R.string.group_permissions_updated_thread), defaultTint);

      case CommandArgs.ACCEPTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.group_permissions_updated_thread), defaultTint);

      case CommandArgs.REJECTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.group_permissions_rejected_thread), defaultTint);
    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown incoming Fence Permission event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeIncomingUpdateMaxMembers(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand fenceCommand       = ufsrvCommand.getFenceCommand();
    int commandArgs                 = fenceCommand.getHeader().getArgs();
    String sourceUser               = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    Recipient recipientSource       =  Recipient.live(sourceUser).get();

    switch (commandArgs)
    {
      case CommandArgs.UPDATED_VALUE:
        return emphasisAdded(context, context.getString(R.string.maxmembers_updated_thread), defaultTint);

      case CommandArgs.ACCEPTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.maxmembers_updated_by_you, recipientSource.getDisplayName(), fenceCommand.getFences(0).getMaxmembers()), defaultTint);

      case CommandArgs.REJECTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.maxmembers_request_rejected_thread), defaultTint);

    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown incoming  group MAXMEMBERS event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeIncomingUpdateDeliveryMode(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand fenceCommand       = ufsrvCommand.getFenceCommand();
    int commandArgs                 = fenceCommand.getHeader().getArgs();
    String sourceUser               = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    Recipient recipientSource       =  Recipient.live(sourceUser).get();

    switch (commandArgs)
    {
      case CommandArgs.UPDATED_VALUE:
        return emphasisAdded(context, context.getString(R.string.delivery_mode_updated_thread), defaultTint);

      case CommandArgs.ACCEPTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.delivery_mode_updated_by_you, fenceCommand.getFences(0).getDeliveryMode().getNumber()), defaultTint);

      case CommandArgs.REJECTED_VALUE:
        return emphasisAdded(context, context.getString(R.string.delivery_mode_request_rejected_thread), defaultTint);

    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown incoming  group MAXMEMBERS event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeIncomingUpdateState(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand  fenceCommand  = ufsrvCommand.getFenceCommand();
    int           commandArgs   = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case SignalServiceProtos.CommandArgs.SYNCED_VALUE:
        return emphasisAdded(context, context.getString(R.string.group_information_has_been_updated), defaultTint);

    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown incoming  group STATE event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), defaultTint);
  }

  private static @NonNull LiveData<SpannableString> describeIncomingLeave(Context context, UfsrvCommandWire ufsrvCommand, int defaultTint)
  {
    FenceCommand fenceCommand     = ufsrvCommand.getFenceCommand();
    int           commandArgs     = fenceCommand.getHeader().getArgs();
    String        sourceUser      = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean       isMe            = TextUtils.isEmpty(sourceUser)|| sourceUser.equals(UfsrvUid.UndefinedUfsrvUid) || sourceUser.equals(TextSecurePreferences.getUfsrvUserId(context));

    StringBuilder description     = new StringBuilder();

    Recipient    recipient;
    if (isMe) {
      sourceUser = "You";
    } else      {
      recipient      =  Recipient.live(sourceUser).get();
      sourceUser = String.format("%s ", recipient.getDisplayName());
    }
    description.append(sourceUser);

    switch (commandArgs)
    {
      case  CommandArgs.ACCEPTED_VALUE:
      case  CommandArgs.GEO_BASED_VALUE://we roll them into one for now

        return emphasisAdded(context, description.append(context.getString(R.string.have_left_the_group_thread)).toString(), R.drawable.ic_update_group_leave_16, defaultTint);

      case  CommandArgs.SYNCED_VALUE:
        return emphasisAdded(context, description.append(context.getString(R.string.have_left_the_group_thread)).toString(), R.drawable.ic_update_group_leave_16, defaultTint);

      case CommandArgs.UNINVITED_VALUE:
        //semantics slightly different for uninvite, as it could originate from server, not user
        String invitedUser = UfsrvUid.EncodedfromSerialisedBytes(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray());

        isMe = TextUtils.isEmpty(sourceUser) || invitedUser.equals(TextSecurePreferences.getUfsrvUserId(context));
        if (isMe) return emphasisAdded(context, description.append(context.getString(R.string.you_no_longer_on_invite_list_thread)).toString(), R.drawable.ic_update_group_leave_16, defaultTint);

        recipient =  Recipient.live(invitedUser).get();
        return emphasisAdded(context, context.getString(R.string.user_no_longer_on_invited_list_thread, recipient.getDisplayName()), defaultTint);

    }

    return emphasisAdded(context, String.format(Locale.getDefault(), "Unknown incoming group leave event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs), R.drawable.ic_update_group_leave_16, defaultTint);
  }
  //

  @Override
  public void onRecipientChanged(@NonNull Recipient recipient) {
    if (this.recipient == null || !this.recipient.getId().equals(recipient.getId())) {
      Log.w(TAG, "Bad change! Local recipient doesn't match. Ignoring. Local: " + (this.recipient == null ? "null" : this.recipient.getId()) + ", Changed: " + recipient.getId());
      return;
    }

    if (highlightSubstring != null) {
      String name = recipient.isSelf() ? getContext().getString(R.string.note_to_self) : recipient.getDisplayName(getContext());
      fromView.setText(recipient, SearchUtil.getHighlightedSpan(locale, SpanUtil::getMediumBoldSpan, new SpannableString(name), highlightSubstring, SearchUtil.MATCH_ALL), true, null);
    } else {
      fromView.setText(recipient, false);
    }
    contactPhotoImage.setAvatar(glideRequests, recipient, !batchMode);
    setBadgeFromRecipient(recipient);
  }

  @Override
  public void onChanged(SpannableString spannableString) {
    setSubjectViewText(spannableString);

    if (typingThreads != null) {
      updateTypingIndicator(typingThreads);
    }
  }
}