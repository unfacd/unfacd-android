package org.thoughtcrime.securesms.contacts;

import android.content.Context;

import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Model for a contact and the various ways it could be represented. Used in situations where we
 * don't want to create Recipients for the wrapped data (like a custom-entered phone number for
 * someone you don't yet have a conversation with).
 *
 */
public final class SelectedContact {
  private final RecipientId recipientId;
  private final String      number;
  private final String      username;
  private final String      ufsrvuid;//AA+

  public static @NonNull SelectedContact forPhone(@Nullable RecipientId recipientId, @NonNull String number) {
    return new SelectedContact(recipientId, number, null, null);
  }

  public static @NonNull SelectedContact forUsername(@Nullable RecipientId recipientId, @NonNull String username) {
    return new SelectedContact(recipientId, null, username, null);
  }

  public static @NonNull SelectedContact forRecipientId(@NonNull RecipientId recipientId) {
    return new SelectedContact(recipientId, null, null, null);
  }

  public static @NonNull SelectedContact forUfsrvUid(@Nullable RecipientId recipientId, @NonNull String ufsrvuid) {//AA+
    return new SelectedContact(recipientId, null, null, ufsrvuid);
  }

  private SelectedContact(@Nullable RecipientId recipientId, @Nullable String number, @Nullable String username, @Nullable String ufsrvuid) {//AA++
    this.recipientId = recipientId;
    this.number      = number;
    this.username    = username;
    this.ufsrvuid    = ufsrvuid;
  }

  public @NonNull RecipientId getOrCreateRecipientId(@NonNull Context context) {
    if (recipientId != null) {
      return recipientId;
    } else if (number != null) {
      return Recipient.external(context, number).getId();
    } else {
      throw new AssertionError();
    }
  }

  /**
   * Returns true when non-null recipient ids match, and false if not.
   * <p>
   * If one or more recipient id is not set, then it returns true iff any other non-null property
   * matches one on the other contact.
   */
  public boolean matches(@Nullable SelectedContact other) {
    if (other == null) return false;

    if (recipientId != null && other.recipientId != null) {
      return recipientId.equals(other.recipientId);
    }

    if (ufsrvuid != null && other.ufsrvuid != null) {
      return ufsrvuid.equals(other.ufsrvuid);//AA+
    }

    return number   != null && number  .equals(other.number)   ||
            username != null && username.equals(other.username);
  }
}