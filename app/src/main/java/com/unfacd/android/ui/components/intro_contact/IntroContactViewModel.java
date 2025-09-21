package com.unfacd.android.ui.components.intro_contact;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.signal.core.util.StreamUtil;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.mediasend.Media;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.util.SingleLiveEvent;
import org.signal.libsignal.protocol.util.Pair;
import java.util.Optional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public class IntroContactViewModel extends ViewModel {
  private static final String TAG = Log.tag(IntroContactViewModel.class);

  private final IntroContactRepository          repository;
  private final SingleLiveEvent<Event>          events;
  private final MutableLiveData<AvatarState>    avatar;
  private       Media                           avatarMedia;

  private byte[] previousAvatar;

  private IntroContactViewModel(@NonNull IntroContactRepository repository) {
    this.repository   = repository;
    this.events       = new SingleLiveEvent<>();
    this.avatar       = new MutableLiveData<>();
  }

  public LiveData<Event> getEvents() {
    return events;
  }

  public @NonNull LiveData<AvatarState> getAvatar() {
    return avatar;
  }

  /**
   * Load avatar from local storage
   * @param avatarId
   */
  void loadAvatar(@NonNull String avatarId)
  {
    repository.loadAvatar(avatarId, avatarBytes -> avatar.postValue(AvatarState.loaded(avatarBytes)));
  }

  void loadAvatarBytes(@NonNull byte[] avatarBytes)
  {
    avatar.postValue(AvatarState.loaded(avatarBytes));
  }

  /**
   * Load avatar byte stream after being selected via camera capture or gallery storage
   * @param context
   * @param media
   */
  public void onAvatarSelected(@NonNull Context context, @Nullable Media media) {
    previousAvatar = avatar.getValue() != null ? avatar.getValue().getAvatar() : null;

    if (media == null) {
      avatar.postValue(AvatarState.loading(null));
      repository.clearAvatar(context, result -> {
        switch (result) {
          case SUCCESS:
            avatar.postValue(AvatarState.loaded(null));
            previousAvatar = null;
            break;
          case FAILURE_NETWORK:
            avatar.postValue(AvatarState.loaded(previousAvatar));
            events.postValue(new IntroContactViewModel.Event(EventType.AVATAR_NETWORK_FAILURE, ""));
            break;
        }
      });
    } else {
      SignalExecutors.BOUNDED.execute(() -> {
        try {
          InputStream stream = BlobProvider.getInstance().getStream(context, media.getUri());
          byte[]      data   = StreamUtil.readFully(stream);

          avatar.postValue(AvatarState.loading(data));

          repository.setAvatar(context, data, media.getMimeType(), result -> {
            switch (result) {
              case SUCCESS:
                avatar.postValue(AvatarState.loaded(data));
                previousAvatar = data;
                break;
              case FAILURE_NETWORK:
                avatar.postValue(AvatarState.loaded(previousAvatar));
                events.postValue(new IntroContactViewModel.Event(EventType.AVATAR_NETWORK_FAILURE, ""));
                break;
            }
          });
        } catch (IOException e) {
          Log.w(TAG, "Failed to save avatar!", e);
          events.postValue(new IntroContactViewModel.Event(EventType.AVATAR_DISK_FAILURE, ""));
        }
      });
    }
  }

  public boolean canRemoveAvatar() {
    return avatar.getValue() != null;
  }

  public @Nullable Media getAvatarMedia() {
    return avatarMedia;
  }

  public void setAvatarMedia(@Nullable Media avatarMedia) {
    this.avatarMedia = avatarMedia;
  }

  void sendIntroRequest(Optional<Pair<Long, IntroContactDescriptor>> descriptorProvided, final String handle, final String message, final byte[] avatar)
  {
    repository.sendIntroMessage(descriptorProvided, handle, message, avatar);
    events.postValue(new IntroContactViewModel.Event(EventType.INTRO_SENT_WITH_SUCCESS, handle));
  }


  /**
   * User response action to IntroMessage.
   * @param descriptor
   * @param action Accept or ignore
   */
  void setIntroContactStatus(Pair<Long, IntroContactDescriptor> descriptor, int action)
  {
    repository.setIntroContactStatus(descriptor, action);
    events.postValue(new IntroContactViewModel.Event(EventType.INTRO_ACTION_WITH_SUCCESS, action, descriptor.second().getAddressable().toString()));
  }

  enum EventType {
    INTRO_SENT_WITH_SUCCESS,
    INTRO_SENT_WITH_ERROR,
    INTRO_ACTION_WITH_SUCCESS,
    AVATAR_NETWORK_FAILURE, AVATAR_DISK_FAILURE
  }

  public static final class Event {

    private final EventType eventType;
    private final int action;
    private final String    number;

    private Event(@NonNull EventType eventType, int action) {
      this.eventType = eventType;
      this.action = action;
      this.number    = null;
    }

    private Event(@NonNull EventType eventType, @NonNull String number) {
      this.eventType = eventType;
      this.action = 0;
      this.number    = number;
    }

    private Event(@NonNull EventType eventType, int action, @NonNull String number) {
      this.eventType = eventType;
      this.action = action;
      this.number    = number;
    }

    public @Nullable int getAction() {
      return action;
    }

    public @Nullable String getNumber() {
      return number;
    }

    public @NonNull EventType getEventType() {
      return eventType;
    }
  }

  public static class AvatarState {
    private final byte[]       avatar;
    private final LoadingState loadingState;

    public AvatarState(@Nullable byte[] avatar, @NonNull LoadingState loadingState) {
      this.avatar       = avatar;
      this.loadingState = loadingState;
    }

    private static @NonNull AvatarState none() {
      return new AvatarState(null, LoadingState.LOADED);
    }

    private static @NonNull AvatarState loaded(@Nullable byte[] avatar) {
      return new AvatarState(avatar, LoadingState.LOADED);
    }

    private static @NonNull AvatarState loading(@Nullable byte[] avatar) {
      return new AvatarState(avatar, LoadingState.LOADING);
    }

    public @Nullable byte[] getAvatar() {
      return avatar;
    }

    public LoadingState getLoadingState() {
      return loadingState;
    }
  }

  public enum LoadingState {
    LOADING, LOADED
  }

  public static class Factory implements ViewModelProvider.Factory {

    private final IntroContactRepository repository;

    public Factory(@NonNull IntroContactRepository repository) {
      this.repository = repository;
    }

    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return Objects.requireNonNull(modelClass.cast(new IntroContactViewModel(repository)));
    }
  }
}