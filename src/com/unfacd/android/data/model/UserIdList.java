package com.unfacd.android.data.model;

import java.util.List;

public class UserIdList {

  private List<Long> userIds;

  public UserIdList(List<Long> userIds) {
    this.userIds = userIds;
  }

  public UserIdList() {}

  public List<Long> getUserIds() {
    return userIds;
  }
}
