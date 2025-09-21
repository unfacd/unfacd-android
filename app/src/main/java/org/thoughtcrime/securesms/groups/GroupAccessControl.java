package org.thoughtcrime.securesms.groups;

import com.unfacd.android.R;

import androidx.annotation.StringRes;

public enum GroupAccessControl {
  ALL_MEMBERS(R.string.GroupManagement_access_level_all_members),
  ONLY_ADMINS(R.string.GroupManagement_access_level_only_admins),
  NO_ONE(R.string.GroupManagement_access_level_no_one),
  ALLOWED(R.string.GroupManagement_access_level_allowed);//AA+

  private final @StringRes int string;

  GroupAccessControl(@StringRes int string) {
    this.string = string;
  }

  public @StringRes int getString() {
    return string;
  }
}