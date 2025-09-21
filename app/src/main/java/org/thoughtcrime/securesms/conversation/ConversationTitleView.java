package org.thoughtcrime.securesms.conversation;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.unfacd.android.R;
import com.unfacd.android.ui.components.PairedGroupName;
import com.unfacd.android.utils.Utils;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.avatar.view.AvatarView;
import org.thoughtcrime.securesms.badges.BadgeImageView;
import org.thoughtcrime.securesms.database.model.StoryViewState;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.ContextUtil;
import org.thoughtcrime.securesms.util.DrawableUtil;
import org.thoughtcrime.securesms.util.ExpirationUtil;
import org.thoughtcrime.securesms.util.ViewUtil;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;


public class ConversationTitleView extends RelativeLayout {

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(ConversationTitleView.class);

  private AvatarView      avatar;
  private BadgeImageView  badge;
  private TextView        title;
  private TextView        subtitle;
  private ImageView       verified;
  private View            subtitleContainer;
  private View            verifiedSubtitle;
  private View            expirationBadgeContainer;
  private TextView        expirationBadgeTime;

  public ConversationTitleView(Context context) {
    this(context, null);
  }

  public ConversationTitleView(Context context, AttributeSet attrs) {
    super(context, attrs);

  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();

    this.title                    = findViewById(R.id.title);
    this.badge                    = findViewById(R.id.badge);
    this.subtitle                 = findViewById(R.id.subtitle);
    this.verified                 = findViewById(R.id.verified_indicator);
    this.subtitleContainer        = findViewById(R.id.subtitle_container);
    this.verifiedSubtitle         = findViewById(R.id.verified_subtitle);
    this.avatar                   = findViewById(R.id.contact_photo_image);
    this.expirationBadgeContainer = findViewById(R.id.expiration_badge_container);
    this.expirationBadgeTime      = findViewById(R.id.expiration_badge);

    ViewUtil.setTextViewGravityStart(this.title, getContext());
    ViewUtil.setTextViewGravityStart(this.subtitle, getContext());
  }

  public void showExpiring(@NonNull LiveRecipient recipient) {
    expirationBadgeTime.setText(ExpirationUtil.getExpirationAbbreviatedDisplayValue(getContext(), recipient.get().getExpiresInSeconds()));
    expirationBadgeContainer.setVisibility(View.VISIBLE);
    updateSubtitleVisibility();
  }

  public void clearExpiring() {
    expirationBadgeContainer.setVisibility(View.GONE);
    updateSubtitleVisibility();
  }

  public void setTitle(@NonNull GlideRequests glideRequests, @Nullable Recipient recipient) {
    this.subtitleContainer.setVisibility(View.VISIBLE);
    if   (recipient == null) setComposeTitle();
    else                     setRecipientTitle(recipient);

    Drawable startDrawable = null;
    Drawable endDrawable   = null;

    if (recipient != null && recipient.isBlocked()) {
      startDrawable = ContextUtil.requireDrawable(getContext(), R.drawable.ic_block_white_18dp);
    } else if (recipient != null && recipient.isMuted()) {
      startDrawable = ContextUtil.requireDrawable(getContext(), R.drawable.ic_bell_disabled_16);
      startDrawable.setBounds(0, 0, ViewUtil.dpToPx(18), ViewUtil.dpToPx(18));
    }

    if (recipient != null && recipient.isSystemContact() && !recipient.isSelf()) {
      endDrawable = ContextCompat.getDrawable(getContext(), R.drawable.ic_profile_circle_outline_16);
    }

    if (startDrawable != null) {
      startDrawable = DrawableUtil.tint(startDrawable, ContextCompat.getColor(getContext(), R.color.signal_inverse_transparent_80));
    }

    if (endDrawable != null) {
      endDrawable = DrawableUtil.tint(endDrawable, ContextCompat.getColor(getContext(), R.color.signal_inverse_transparent_80));
    }

    if (recipient != null && recipient.showVerified()) {
      endDrawable = ContextUtil.requireDrawable(getContext(), R.drawable.ic_official_24);
    }

    title.setCompoundDrawablesRelativeWithIntrinsicBounds(startDrawable, null, endDrawable, null);

    if (recipient != null) {
      this.avatar.displayChatAvatar(glideRequests, recipient, false);
    }

    if (recipient == null || recipient.isSelf()) {
      badge.setBadgeFromRecipient(null);
    } else {
      badge.setBadgeFromRecipient(recipient);
    }

    updateVerifiedSubtitleVisibility();

    //AA+ this seems necessary in code. xml truncates text
    this.title.setHorizontallyScrolling(true);
    this.title.setFocusable(true);
    this.title.setFocusableInTouchMode(true);
    this.title.setMarqueeRepeatLimit(3);
    this.title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
    this.title.setSelected(true);
    this.title.post(new Runnable() {
      @Override
      public void run() {//http://www.synaptica.info/en/2015/05/09/avoid-scrolling-reset-marquee-ellipsize-android-textview/
        //AA ensure "LinearLayout.LayoutParams" reflects what's in the layoutfileconversation_title_view.xml (e.g no RelativeLayout)
        title.setLayoutParams(new LinearLayout.LayoutParams(title.getWidth(), title.getHeight()));
      }
    });
    new Handler(Looper.getMainLooper()).postDelayed (new Runnable() {
      @Override
      public void run() {
        title.setEllipsize(TextUtils.TruncateAt.END);
      }
    }, 20000);
  }

  public void setStoryRingFromState(@NonNull StoryViewState storyViewState) {
    avatar.setStoryRingFromState(storyViewState);
  }

  public void setVerified(boolean verified) {
//    this.verified.setVisibility(verified ? View.VISIBLE : View.GONE); //AA-
    this.verified.setVisibility(View.GONE); //AA+ disabled
    updateVerifiedSubtitleVisibility();
  }

  private void setComposeTitle() {
    this.title.setText(R.string.ConversationActivity_compose_message);
    this.subtitle.setText(null);
    updateSubtitleVisibility();
  }

  private void setRecipientTitle(@NonNull Recipient recipient) {
    if      (recipient.isGroup()) setGroupRecipientTitle(recipient);
    else if (recipient.isSelf())  setSelfTitle();
    else                          setIndividualRecipientTitle(recipient);
  }

  private void setGroupRecipientTitle(Recipient recipient) {
    //AA+
      SpannableStringBuilder builder;
      Optional<SpannableString> privateGroupName  =  new PairedGroupName(getContext(), Recipient.live(recipient.getId())).getStylisedName();
      if (privateGroupName.isPresent()) builder   = new SpannableStringBuilder(privateGroupName.get());
      else builder= new SpannableStringBuilder(recipient.getDisplayName());
      builder.setSpan(new StyleSpan(Typeface.NORMAL), 0, builder.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
      this.title.setText(builder);
    //

    //AA+ todo: this check should happen to paired groups only
    if (recipient.getParticipants().size() == 2) {
      for (Recipient member: recipient.getParticipants()) {
        if ((member.requireUfsrvUid().compareTo(Recipient.self().getUfsrvUid())) != 0) {
          if (member.isPresenceShared()) {
            this.subtitle.setText (Utils.formatPresenceInformation(getContext(), member));
            updateSubtitleVisibility();
            this.subtitleContainer.setVisibility(VISIBLE);
          }
        }
      }
    } else {
      this.subtitle.setVisibility(View.GONE);
    }
    //
  }

/*  private void setGroupRecipientTitle(@NonNull Recipient recipient) {
    this.title.setText(recipient.getDisplayName(getContext()));
    this.subtitle.setText(Stream.of(recipient.getParticipants())
                                  .sorted((a, b) -> Boolean.compare(a.isSelf(), b.isSelf()))
                                  .map(r -> r.isSelf() ? getResources().getString(R.string.ConversationTitleView_you)
                                                       : r.getDisplayName(getContext()))
                                  .collect(Collectors.joining(", ")));

    updateSubtitleVisibility();
  }*/

  private void setSelfTitle() {
    this.title.setText(R.string.note_to_self);
    this.subtitleContainer.setVisibility(View.GONE);
  }

  private void setIndividualRecipientTitle(@NonNull Recipient recipient) {
    final String displayName = recipient.getDisplayNameOrUsername(getContext());
    this.title.setText(displayName);
    this.subtitle.setText(null);
    updateSubtitleVisibility();
    updateVerifiedSubtitleVisibility();
  }

  private void updateVerifiedSubtitleVisibility() {
    verifiedSubtitle.setVisibility(subtitle.getVisibility() != VISIBLE && verified.getVisibility() == VISIBLE ? VISIBLE : GONE);
  }

  private void updateSubtitleVisibility() {
    subtitle.setVisibility(expirationBadgeContainer.getVisibility() != VISIBLE && !TextUtils.isEmpty(subtitle.getText()) ? VISIBLE : GONE);
    updateVerifiedSubtitleVisibility();
  }
}
