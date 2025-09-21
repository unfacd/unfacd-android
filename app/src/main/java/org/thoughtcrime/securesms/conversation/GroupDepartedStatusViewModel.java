package org.thoughtcrime.securesms.conversation;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import org.thoughtcrime.securesms.database.DatabaseContentProviders;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.DatabaseObserver;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.util.concurrent.SerialMonoLifoExecutor;
import org.signal.core.util.concurrent.SignalExecutors;

import java.util.concurrent.Executor;

public class GroupDepartedStatusViewModel extends ViewModel {

  private static final Executor EXECUTOR = new SerialMonoLifoExecutor(SignalExecutors.BOUNDED);

  private final Application                      context;
  private final MutableLiveData<GroupId>         groupId = new MutableLiveData<>(GroupId.v2orThrow(GroupId.requireEmptyId()));
  private final LiveData<Integer>                departedStatus;

  private DatabaseObserver.Observer              observer;

  public GroupDepartedStatusViewModel() {
    this.context      = ApplicationDependencies.getApplication();
    this.departedStatus = Transformations.switchMap(Transformations.distinctUntilChanged(groupId), id -> {

      MutableLiveData<Integer> leaveMode = new MutableLiveData<>(new Integer(-1));

      if (groupId.equals(GroupId.ENCODED_UNDEFINED_GROUP_PREFIX)) {
        return leaveMode;
      }

      this.observer = () -> {
        leaveMode.postValue(getLeaveMode(context, id));
      };

      ApplicationDependencies.getDatabaseObserver().registerGroupsObserver(id, this.observer);

      return leaveMode;
    });
  }

  void setGroupIdId(GroupId groupIdId) {
    this.groupId.setValue(groupIdId);
  }

  void clearGroupIdId() {
    this.groupId.postValue(GroupId.v2orThrow(GroupId.requireEmptyId()));
  }

  @NonNull LiveData<Integer> getDepartedStatus() {
    return departedStatus;
  }

  private Integer getLeaveMode(@NonNull Context context, GroupId groupId) {
    GroupDatabase groupDatabase     = SignalDatabase.groups();
    int             groupMode        = groupDatabase.getGroupMode(groupId);

    return Integer.valueOf(groupMode);
  }

  @Override
  protected void onCleared() {
    if (observer != null) {
      ApplicationDependencies.getDatabaseObserver().unregisterObserver(observer);
    }
  }
}