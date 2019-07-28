package org.thoughtcrime.securesms.conversation;

import com.unfacd.android.R;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import com.unfacd.android.ui.components.PrivateGroupForTwoName;
import com.unfacd.android.utils.UfsrvFenceUtils;
import com.unfacd.android.utils.Utils;

import org.thoughtcrime.securesms.components.AvatarImageView;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.whispersystems.libsignal.util.guava.Optional;


public class ConversationTitleView extends RelativeLayout {

  @SuppressWarnings("unused")
  private static final String TAG = ConversationTitleView.class.getSimpleName();

  private View            content;
  private AvatarImageView avatar;
  private TextView        title;
  private TextView        subtitle;
  private ImageView       verified;
  private View            subtitleContainer;
  private View            verifiedSubtitle;

  public ConversationTitleView(Context context) {
    this(context, null);
  }

  public ConversationTitleView(Context context, AttributeSet attrs) {
    super(context, attrs);

  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();

    this.content           = ViewUtil.findById(this, R.id.content);
    this.title             = ViewUtil.findById(this, R.id.title);
    this.subtitle          = ViewUtil.findById(this, R.id.subtitle);
    this.verified          = ViewUtil.findById(this, R.id.verified_indicator);
    this.subtitleContainer = ViewUtil.findById(this, R.id.subtitle_container);
    this.verifiedSubtitle  = ViewUtil.findById(this, R.id.verified_subtitle);
    this.avatar            = ViewUtil.findById(this, R.id.contact_photo_image);

    ViewUtil.setTextViewGravityStart(this.title, getContext());
    ViewUtil.setTextViewGravityStart(this.subtitle, getContext());
  }

  public void setTitle(@NonNull GlideRequests glideRequests, @Nullable Recipient recipient) {
    this.subtitleContainer.setVisibility(View.VISIBLE);
    if      (recipient == null) setComposeTitle();
    else                        setRecipientTitle(recipient);

    if (recipient != null && recipient.isBlocked()) {
      title.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_block_white_18dp, 0, 0, 0);
    } else if (recipient != null && recipient.isMuted()) {
      title.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_volume_off_white_18dp, 0, 0, 0);
    } else {
      title.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
    }

    if (recipient != null && UfsrvFenceUtils.isAvatarUfsrvIdLoaded(recipient.getGroupAvatarUfsrvId())) {// addition conditional
      this.avatar.setAvatar(glideRequests, recipient, false);
    } else this.avatar.setVisibility(GONE);//

    updateVerifiedSubtitleVisibility();

    // this seems necessary in code. xml truncates text
    this.title.setHorizontallyScrolling(true);
    this.title.setFocusable(true);
    this.title.setFocusableInTouchMode(true);
    this.title.setMarqueeRepeatLimit(3);
    this.title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
    this.title.setSelected(true);
    this.title.post(new Runnable() {
      @Override
      public void run() {//http://www.synaptica.info/en/2015/05/09/avoid-scrolling-reset-marquee-ellipsize-android-textview/
        // ensure "LinearLayout.LayoutParams" reflects what's in the layoutfileconversation_title_view.xml (e.g no RelativeLayout)
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

  //
  String getTitle ()
  {
    return title.getText().toString();
  }
  //

  public void setVerified(boolean verified) {
//    this.verified.setVisibility(verified ? View.VISIBLE : View.GONE); //-
    this.verified.setVisibility(View.GONE); // disabled
    updateVerifiedSubtitleVisibility();
  }

  @Override
  public void setOnClickListener(@Nullable OnClickListener listener) {
    this.content.setOnClickListener(listener);
    this.avatar.setOnClickListener(listener);
  }

  @Override
  public void setOnLongClickListener(@Nullable OnLongClickListener listener) {
    this.content.setOnLongClickListener(listener);
    this.avatar.setOnLongClickListener(listener);
  }

  private void setComposeTitle() {
    this.title.setText(R.string.ConversationActivity_compose_message);
    this.subtitle.setText(null);
    this.subtitle.setVisibility(View.GONE);
  }

  private void setRecipientTitle(Recipient recipient) {
    if      (recipient.isGroupRecipient())           setGroupRecipientTitle(recipient);
    else if (recipient.isLocalNumber())              setSelfTitle();
    else if (TextUtils.isEmpty(recipient.getName())) setNonContactRecipientTitle(recipient);
    else                                             setContactRecipientTitle(recipient);
  }

  private void setGroupRecipientTitle(Recipient recipient) {
    String localNumber = TextSecurePreferences.getUfsrvUserId(getContext());// ufsrv

    //
      SpannableStringBuilder builder;
      Optional<SpannableString> privateGroupName  =  new PrivateGroupForTwoName(getContext(), recipient).getStylisedName();
      if (privateGroupName.isPresent()) builder   = new SpannableStringBuilder(privateGroupName.get());
      else builder= new SpannableStringBuilder(recipient.getDisplayName());
      builder.setSpan(new StyleSpan(Typeface.NORMAL), 0, builder.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
      this.title.setText(builder);
    //

    //- dont support this at thi stage
//    this.subtitle.setText(Stream.of(recipient.getParticipants())
//                                  .filter(r -> !r.getAddress().serialize().equals(localNumber))
//                                  .map(Recipient::getDisplayName)//toShortString) //-
//                                  .collect(Collectors.joining(", ")));

//    this.subtitle.setVisibility(View.VISIBLE);

    //
    if (recipient.getParticipants().size() == 2) {
      for (Recipient member: recipient.getParticipants()) {
        if ((member.getAddress().serialize().compareTo(localNumber))!=0) {
          if (member.isPresenceShared()) {
            this.subtitle.setText (Utils.formatPresenceInformation(getContext(), member));
            this.subtitle.setVisibility(View.VISIBLE);
            this.subtitleContainer.setVisibility(VISIBLE);
          }
        }
      }
    } else {
      this.subtitle.setVisibility(View.GONE);
    }
    //
  }

  private void setSelfTitle() {
    this.title.setText(R.string.note_to_self);
    this.subtitleContainer.setVisibility(View.GONE);
  }

  private void setNonContactRecipientTitle(Recipient recipient) {
    this.title.setText(recipient.getAddress().serialize());

    if (TextUtils.isEmpty(recipient.getProfileName())) {
      this.subtitle.setText(null);
      this.subtitle.setVisibility(View.GONE);
    } else {
      this.subtitle.setText("~" + recipient.getProfileName());
      this.subtitle.setVisibility(View.VISIBLE);
    }
  }

  private void setContactRecipientTitle(Recipient recipient) {
    this.title.setText(recipient.getName());

    if (TextUtils.isEmpty(recipient.getCustomLabel())) {
      this.subtitle.setText(null);
      this.subtitle.setVisibility(View.GONE);
//      this.subtitleContainer.setVisibility(View.GONE);
    } else {
      this.subtitle.setText(recipient.getCustomLabel());
      this.subtitle.setVisibility(View.VISIBLE);
//      this.subtitleContainer.setVisibility(View.VISIBLE);
    }
  }

  private void updateVerifiedSubtitleVisibility() {
    verifiedSubtitle.setVisibility(subtitle.getVisibility() != VISIBLE && verified.getVisibility() == VISIBLE ? VISIBLE : GONE);
  }

}
