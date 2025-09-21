package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.util.AttributeSet;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.ui.components.PairedGroupName;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.components.emoji.SimpleEmojiTextView;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientForeverObserver;
import org.thoughtcrime.securesms.util.ContextUtil;
import org.thoughtcrime.securesms.util.SpanUtil;
import org.thoughtcrime.securesms.util.ViewUtil;
import java.util.Optional;

import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class FromTextView extends SimpleEmojiTextView implements RecipientForeverObserver {//AA+ Recipients.RecipientForeverObserver

  private static final String TAG = Log.tag(FromTextView.class);

  //AA+
  PairedGroupName pairedGroupName = null;

  public FromTextView(Context context) {
    super(context);
  }

  public FromTextView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public void setText(Recipient recipient) {
    setText(recipient, true);
  }

  public void setText(Recipient recipient, boolean read) {
    setText(recipient, read, null);
  }

  public void setText(Recipient recipient, boolean read, @Nullable String suffix)
  {
    setText(recipient, recipient.getDisplayName(getContext()), read, suffix);
  }

  public void setText(Recipient recipient, @Nullable CharSequence fromString, boolean read, @Nullable String suffix) {
    SpannableStringBuilder builder;//AA+
    SpannableString fromSpan = null;//AA+

    //AA+ delta
    Optional<SpannableString> privateGroupName  =  Optional.empty();
    pairedGroupName = new PairedGroupName(getContext(), Recipient.live(recipient.getId()));
    privateGroupName = pairedGroupName.getStylisedName();

    if (privateGroupName.isPresent()) {
      builder   = new SpannableStringBuilder(privateGroupName.get());
      Recipient.live(pairedGroupName.getOtherMember().getId()).observeForever(this);
      fromSpan = new SpannableString(privateGroupName.get());
    } else {
      builder = new SpannableStringBuilder(fromString);
      fromSpan = new SpannableString(fromString);
      pairedGroupName  = null;
    }
    //

//    fromSpan.setSpan(new StyleSpan(typeface), 0, builder.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
    fromSpan.setSpan(getFontSpan(!read), 0, fromSpan.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);

    if (suffix != null) {
      builder.append(suffix);
    }

    if (recipient.showVerified()) {
      Drawable official = ContextUtil.requireDrawable(getContext(), R.drawable.ic_official_20);
      official.setBounds(0, 0, ViewUtil.dpToPx(20), ViewUtil.dpToPx(20));

      builder.append(" ")
              .append(SpanUtil.buildCenteredImageSpan(official));
    }

    setText(builder);

    if      (recipient.isBlocked()) setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_block_grey600_18dp, 0, 0, 0);
    else if (recipient.isMuted())   setCompoundDrawablesRelativeWithIntrinsicBounds(getMuted(), null, null, null);
    else                            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
  }

  private Drawable getMuted() {
    Drawable mutedDrawable = Objects.requireNonNull(ContextCompat.getDrawable(getContext(), R.drawable.ic_bell_disabled_16));

    mutedDrawable.setBounds(0, 0, ViewUtil.dpToPx(18), ViewUtil.dpToPx(18));
    mutedDrawable.setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(getContext(), R.color.signal_icon_tint_secondary), PorterDuff.Mode.SRC_IN));

    return mutedDrawable;
  }

  private CharacterStyle getFontSpan(boolean isBold) {
    return isBold ? SpanUtil.getBoldSpan() : SpanUtil.getNormalSpan();
  }

  //AA+
  @Override
  public void onDetachedFromWindow ()
  {
    super.onDetachedFromWindow();

    if (pairedGroupName != null)
      Recipient.live(pairedGroupName.getOtherMember().getId()).removeForeverObserver(this);
  }

  @Override
  public void onRecipientChanged(@NonNull Recipient recipient) {
    if (pairedGroupName != null) {
        Log.d(TAG, String.format("onRecipientChanged: Called on '%s'", pairedGroupName.getOtherMember().get().getGroupName(ApplicationContext.getInstance())));
        setText(pairedGroupName.getGroupRecipient().get());
    }
  }
  //

}