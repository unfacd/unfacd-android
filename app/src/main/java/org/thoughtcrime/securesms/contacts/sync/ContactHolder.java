package org.thoughtcrime.securesms.contacts.sync;

import android.net.Uri;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.profiles.ProfileName;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class ContactHolder {

  private static final String TAG = Log.tag(ContactHolder.class);

  private final List<PhoneNumberRecord> phoneNumberRecords = new LinkedList<>();

  private StructuredNameRecord structuredNameRecord;

  public void addPhoneNumberRecords(@NonNull List<PhoneNumberRecord> phoneNumberRecords) {
    this.phoneNumberRecords.addAll(phoneNumberRecords);
  }

  public void setStructuredNameRecord(@NonNull StructuredNameRecord structuredNameRecord) {
    this.structuredNameRecord = structuredNameRecord;
  }

  void commit(@NonNull RecipientDatabase.BulkOperationsHandle handle) {
    for (PhoneNumberRecord phoneNumberRecord : phoneNumberRecords) {
      handle.setSystemContactInfo(phoneNumberRecord.getRecipientId(),
                                  getProfileName(phoneNumberRecord.getDisplayName()),
                                  phoneNumberRecord.getDisplayName(),
                                  phoneNumberRecord.getContactPhotoUri(),
                                  phoneNumberRecord.getContactLabel(),
                                  phoneNumberRecord.getPhoneType(),
                                  Optional.ofNullable(phoneNumberRecord.getContactUri()).map(Uri::toString).orElse(null));
    }
  }

  private @NonNull ProfileName getProfileName(@Nullable String displayName) {
    if (structuredNameRecord != null && structuredNameRecord.hasGivenName()) {
      return structuredNameRecord.asProfileName();
    } else if (displayName != null) {
      return ProfileName.asGiven(displayName);
    } else {
      Log.w(TAG, "Failed to find a suitable display name!");
      return ProfileName.EMPTY;
    }
  }
}