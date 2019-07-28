package org.thoughtcrime.securesms.components;

import com.unfacd.android.R;

import android.content.Context;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.ui.components.PrivateGroupForTwoName;

import org.thoughtcrime.securesms.components.emoji.EmojiTextView;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientModifiedListener;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

public class FromTextView extends EmojiTextView implements RecipientModifiedListener
{// Recipients.RecipientsModifiedListener

  private static final String TAG = FromTextView.class.getSimpleName();

  //
  PrivateGroupForTwoName privateGroupForTwoName = null;

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

  public void setText(Recipient recipient, boolean read, @Nullable String suffix) {
    String fromString = recipient.toShortString();

    int typeface;

    if (!read) {
      typeface = Typeface.BOLD;
    } else {
      typeface = Typeface.NORMAL;
    }

    SpannableStringBuilder builder;//
    SpannableString fromSpan = null;//

    // delta
    Optional<SpannableString> privateGroupName  =  Optional.absent();
    privateGroupForTwoName = new PrivateGroupForTwoName(getContext(), recipient);
    if (privateGroupForTwoName != null) privateGroupName = privateGroupForTwoName.getStylisedName();

    if (privateGroupName.isPresent()) {
      builder   = new SpannableStringBuilder(privateGroupName.get());
      privateGroupForTwoName.getOtherMember().addListener(this);
      fromSpan = new SpannableString(privateGroupName.get());
    } else {
      builder = new SpannableStringBuilder(fromString);
      fromSpan = new SpannableString(fromString);
      privateGroupForTwoName  = null;
    }
    //

    fromSpan.setSpan(new StyleSpan(typeface), 0, builder.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);

    if (suffix != null) {
      builder.append(suffix);
    }

    setText(builder);

    if      (recipient.isBlocked()) setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_block_grey600_18dp, 0, 0, 0);
    else if (recipient.isMuted())   setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_volume_off_grey600_18dp, 0, 0, 0);
    else                            setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
  }


  //
  @Override
  public void onDetachedFromWindow ()
  {
    super.onDetachedFromWindow();

    if (privateGroupForTwoName != null)
      privateGroupForTwoName.getOtherMember().removeListener(this);
  }

  @Override
  public void onModified(final Recipient recipient) {
    if (privateGroupForTwoName != null) {
      Util.runOnMain(() -> {
        Log.d(TAG, String.format("onModified: Called on '%s'", privateGroupForTwoName.getOtherMember().getName()));
        setText(privateGroupForTwoName.getGroupRecipient());
      });
     }
  }
  //

}