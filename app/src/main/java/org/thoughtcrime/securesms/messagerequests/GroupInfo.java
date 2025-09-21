package org.thoughtcrime.securesms.messagerequests;

import androidx.annotation.NonNull;

final class GroupInfo {
  static final GroupInfo ZERO = new GroupInfo(0, 0, "", 0);

  private final int    fullMemberCount;
  private final int    pendingMemberCount;
  private final String description;
  private final int    linkJoiningMemberCount;//AA+

  GroupInfo(int fullMemberCount, int pendingMemberCount, @NonNull String description, int linkJoiningMemberCount) {//AA+ linkJoiningMemberCount
    this.fullMemberCount    = fullMemberCount;
    this.pendingMemberCount = pendingMemberCount;
    this.description        = description;
    this.linkJoiningMemberCount = linkJoiningMemberCount;//AA+
  }

  int getFullMemberCount() {
    return fullMemberCount;
  }

  int getPendingMemberCount() {
    return pendingMemberCount;
  }

  public @NonNull String getDescription() {
    return description;
  }

  int getLinkJoiningMemberCount() {
    return linkJoiningMemberCount;
  }
}