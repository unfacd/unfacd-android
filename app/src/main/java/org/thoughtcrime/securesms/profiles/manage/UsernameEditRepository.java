package org.thoughtcrime.securesms.profiles.manage;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.utils.IntentServiceCheckNicknameAvailability;
import com.unfacd.android.utils.NicknameAvailabilityReceiver;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.signal.core.util.concurrent.SignalExecutors;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.UsernameMalformedException;
import org.whispersystems.signalservice.api.push.exceptions.UsernameTakenException;

import java.io.IOException;
import java.util.concurrent.Executor;

class UsernameEditRepository {

  private static final String TAG = Log.tag(UsernameEditRepository.class);

  private final Application                 application;
  private final SignalServiceAccountManager accountManager;
  private final Executor                    executor;

  private NicknameAvailabilityReceiver nicknameAvailabilityReceiver;//AA+

  UsernameEditRepository() {
    this.application    = ApplicationDependencies.getApplication();
    this.accountManager = ApplicationDependencies.getSignalServiceAccountManager();
    this.executor       = SignalExecutors.UNBOUNDED;
  }

  void setUsername(@NonNull String username, @NonNull Callback<UsernameSetResult> callback) {
    executor.execute(() -> callback.onComplete(setUsernameInternal(username)));
  }

  void deleteUsername(@NonNull Callback<UsernameDeleteResult> callback) {
    executor.execute(() -> callback.onComplete(deleteUsernameInternal()));
  }

  @WorkerThread
  private @NonNull UsernameSetResult setUsernameInternal(@NonNull String username) {
    try {
      accountManager.setUsername(username);
      //SignalDatabase.recipients().setUsername(Recipient.self().getId(), username);
      Log.i(TAG, "[setUsername] (pending)Successfully set username.");
      return UsernameSetResult.SUCCESS;
    } catch (UsernameTakenException e) {
      Log.w(TAG, "[setUsername] Username taken.");
      return UsernameSetResult.USERNAME_UNAVAILABLE;
    } catch (UsernameMalformedException e) {
      Log.w(TAG, "[setUsername] Username malformed.");
      return UsernameSetResult.USERNAME_INVALID;
    } catch (IOException e) {
      Log.w(TAG, "[setUsername] Generic network exception.", e);
      return UsernameSetResult.NETWORK_ERROR;
    }
  }

  @WorkerThread
  private @NonNull UsernameDeleteResult deleteUsernameInternal() {
    try {
      accountManager.deleteUsername();
      SignalDatabase.recipients().setUsername(Recipient.self().getId(), null);
      Log.i(TAG, "[deleteUsername] Successfully deleted the username.");
      return UsernameDeleteResult.SUCCESS;
    } catch (IOException e) {
      Log.w(TAG, "[deleteUsername] Generic network exception.", e);
      return UsernameDeleteResult.NETWORK_ERROR;
    }
  }

  //AA+
  public void setupNicknameAvailabilityServiceReceiver(@NonNull Consumer<Boolean> callback) {
    nicknameAvailabilityReceiver = new NicknameAvailabilityReceiver(new Handler(Looper.getMainLooper()));

    nicknameAvailabilityReceiver.setReceiver((resultCode, resultData) -> {
      boolean isNicknameAvailable = false;
      if (resultCode == 0) {
        isNicknameAvailable = resultData.getBoolean(IntentServiceCheckNicknameAvailability.RESULT_NICKNAME_EXTRA, false);
      }
      callback.accept(isNicknameAvailable);
    });

  }

  public void checkNicknameAvailability(String nickname)
  {
    Intent intent = new Intent(ApplicationContext.getInstance(), IntentServiceCheckNicknameAvailability.class);
    intent.putExtra(IntentServiceCheckNicknameAvailability.NICKNAME_EXTRA, nickname);
    intent.putExtra(IntentServiceCheckNicknameAvailability.PENDING_RESULT_EXTRA, nicknameAvailabilityReceiver);//result carrier object
    ApplicationContext.getInstance().startService(intent);
  }
  //

  enum UsernameSetResult {
    SUCCESS, USERNAME_UNAVAILABLE, USERNAME_INVALID, NETWORK_ERROR
  }

  enum UsernameDeleteResult {
    SUCCESS, NETWORK_ERROR
  }

  enum UsernameAvailableResult {
    TRUE, FALSE, NETWORK_ERROR
  }

  interface Callback<E> {
    void onComplete(E result);
  }
}