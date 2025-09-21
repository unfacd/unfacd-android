package com.unfacd.android.ui.components.intro_contact;

import android.annotation.SuppressLint;
import android.content.Context;

import com.unfacd.android.jobs.UnfacdIntroContactJob;
import com.unfacd.android.locallyaddressable.LocallyAddressable;
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.utils.IntroMessageUtils;

import org.signal.core.util.StreamUtil;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.signal.libsignal.protocol.util.Pair;
import java.util.Optional;

import java.io.IOException;
import java.util.concurrent.Executor;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

@SuppressLint("StaticFieldLeak")
public class IntroContactRepository
{
  private Optional<Pair<Long, TBDIntroContactDescriptor>> descriptorProvided = Optional.empty();

  private byte[]                  avatarBytes;

  private static final String TAG = Log.tag(IntroContactRepository.class);

  private final Context context;

  private static final Executor EXECUTOR = SignalExecutors.BOUNDED;

  IntroContactRepository(@NonNull Context context) {
    this.context = context;
  }

  /**
   * Load avatar bytes where previously stored.
   * @param avatarId serialised ufsrv avatarid
   * @param avatarBytesConsumer
   */
  public void loadAvatar(String avatarId, @NonNull Consumer<byte[]> avatarBytesConsumer) {
    SignalExecutors.BOUNDED.execute(() -> {
      LocallyAddressableUfsrvUid addressableUfsrvUid = LocallyAddressableUfsrvUid.from(RecipientId.UNKNOWN, avatarId);
      Log.d(TAG, String.format("loadAvatar: Loading avatar file: '%s'", addressableUfsrvUid));

      if (AvatarHelper.avatarExists(context, addressableUfsrvUid)) {
        try {
          final byte[] avatarBytes = StreamUtil.readFully(AvatarHelper.getAvatar(context, addressableUfsrvUid));
          avatarBytesConsumer.accept(avatarBytes);
        } catch (IOException e) {
          Log.w(TAG, e);
        }
      }
    });
  }

  //AA currently does nothing other than passing success state
  public void setAvatar(@NonNull Context context, @NonNull byte[] data, @NonNull String contentType, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
//        AvatarHelper.setAvatar(context, Recipient.self().getId(), new ByteArrayInputStream(data));//AA N/A
        callback.accept(Result.SUCCESS);
      } catch (Exception e) {
        Log.w(TAG, "Failed to upload profile during avatar change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  //AA doesn't do anything useful at this stage
  public void clearAvatar(@NonNull Context context, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        callback.accept(Result.SUCCESS);
      } catch (Exception e) {
        Log.w(TAG, "Failed to upload profile during name change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  /**
   * @param descriptor
   * @param action
   */
  void setIntroContactStatus(Pair<Long, IntroContactDescriptor> descriptor, int action)
  {
//    Recipient recipient = Recipient.live(descriptor.second().getAddressable().toString()).get();
    String ufsrvUidTo = descriptor.second().getAddressable().toString();
    if (action == 1) {
         SignalDatabase.unfacdIntroContacts().setResponseStatus(descriptor.first(), ResponseStatus.ACCEPTED, System.currentTimeMillis());
        IntroMessageUtils.sendContactIntroUserResponseMessage(context, ufsrvUidTo, descriptor.second().getEid(), descriptor.second().getTimestampSent(), ResponseStatus.ACCEPTED);
        Recipient.resolvedFromUfsrvUid(ufsrvUidTo);//induce fetching of basic user data
    } else {
       SignalDatabase.unfacdIntroContacts().setResponseStatus(descriptor.first(), ResponseStatus.IGNORED, System.currentTimeMillis());
      IntroMessageUtils.sendContactIntroUserResponseMessage(context,ufsrvUidTo, descriptor.second().getEid(), descriptor.second().getTimestampSent(), ResponseStatus.IGNORED);
    }
  }


  /**
   * Send an outgoing IntroRequest message
   * @param descriptorProvided Temporary packaged up IntroContactDescriptor
   * @param handle user identifying handle used by sender
   * @param message optional message
   * @param avatar optional avatar
   */
  public void
  sendIntroMessage(Optional<Pair<Long, IntroContactDescriptor>> descriptorProvided, final String handle, final String message, final byte[] avatar)
  {
    LocallyAddressable locallyAddressable;
    IntroContactDescriptor descriptorNew;
    long timeNow = System.currentTimeMillis();
    long introId;

    //addressable will be recorded once response is back, also can use 'locallyAddressable = LocallyAddressableUndefined.require();//ufsrvuid unknown'
    descriptorNew = new IntroContactDescriptor(null, handle, message, null, IntroDirection.OUTGOING, timeNow, ResponseStatus.UNSENT, 0, 0);

    introId =  SignalDatabase.unfacdIntroContacts().insertIntroContact(descriptorNew);

    if (descriptorProvided.isPresent()) {
      //todo do we blow off old record?
//         SignalDatabase.unfacdIntroContacts().deleteContactIntro(descriptorProvided.get().first);
    }

    if (avatar != null) {
       SignalDatabase.unfacdIntroContacts().setAvatarBlob(introId, avatar);
    }

    ApplicationDependencies.getJobManager().add(new UnfacdIntroContactJob(introId));

  }

  static public void
  sendIntroMessageUserResponse(IntroContactDescriptor descriptorProvided)
  {


  }

 /* private class
  SendUnfacdIntroContact extends AsyncTask<Void,Void,Integer> {
    final String handle;
    final String message;
    final byte[]avatar;
    long introId = -1;
    Optional<Pair<Long, IntroContactDescriptor>> descriptorProvided;

    public SendUnfacdIntroContact (Optional<Pair<Long, IntroContactDescriptor>> descriptorProvided, final String handle, final String message, final byte[] avatar)
    {
      this.handle   = handle;
      this.message  = message;
      this.avatar   = avatar;
      this.descriptorProvided = descriptorProvided;
    }

    @Override
    protected void onPreExecute () {
    }

    @Override
    protected Integer doInBackground(Void... aVoid) {
      LocallyAddressable locallyAddressable;
      IntroContactDescriptor descriptorNew;
      long timeNow = System.currentTimeMillis();

      if (handle.startsWith("@") || handle.startsWith("+") || LocallyAddressableEmail.isValidEmail(handle)) {
        locallyAddressable = LocallyAddressableUndefined.require();//ufsrviod unknown
        descriptorNew = new IntroContactDescriptor(locallyAddressable, handle, message, null, IntroContactDescriptor.IntroDirection.OUTGOING, timeNow);
      } else {//ufsrvuid
        locallyAddressable = LocallyAddressableUfsrvUid.from(RecipientId.UNKNOWN, handle);
        descriptorNew = new IntroContactDescriptor(locallyAddressable, null, message, null, IntroContactDescriptor.IntroDirection.OUTGOING, timeNow);
      }

      this.introId =  SignalDatabase.unfacdIntroContacts().insertIntroContact(descriptorNew);

      if (descriptorProvided.isPresent()) {
        //todo do we blow off old record?
//         SignalDatabase.unfacdIntroContacts().deleteContactIntro(descriptorProvided.get().first);
      }

      if (avatar != null) {
         SignalDatabase.unfacdIntroContacts().setAvatarBlob(introId, avatar);
      }

      ApplicationDependencies.getJobManager().add(new UnfacdIntroContactJob(introId));

      return 0;
    }

    @Override
    protected void onPostExecute(Integer result) {
      super.onPostExecute(result);
      if (result.intValue() == 0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleFinishedLollipop();
        else                                                       handleFinishedLegacy();
      } else        {
//          Toast.makeText(CreateProfileActivity.this, R.string.CreateProfileActivity_problem_setting_profile, Toast.LENGTH_LONG).show();
      }
    }

  }*/

  /*private class
  RetrieveUnfacdIntroContact extends AsyncTask<Void,Void,Integer> {
    final int action;
    final Optional<Pair<Long, IntroContactDescriptor>> descriptor;

    public RetrieveUnfacdIntroContact ( Optional<Pair<Long, IntroContactDescriptor>> descriptor, int action) {
      this.descriptor = descriptor;
      this.action     = action;
    }

    @Override
    protected void onPreExecute () {
    }

    @Override
    protected Integer doInBackground(Void... aVoid) {
      if (action == 1) {
        Recipient recipient = Recipient.live(descriptor.get().second().getAddressable().toString()).get();
        if (recipient != null) {
           SignalDatabase.unfacdIntroContacts().setResponseStatus(descriptor.get().first(), IntroContactDescriptor.ResponseStatus.ACCEPTED);
        }
      } else {
         SignalDatabase.unfacdIntroContacts().setResponseStatus(descriptor.get().first(), IntroContactDescriptor.ResponseStatus.IGNORED);
      }
      return 0;
    }

    @Override
    protected void onPostExecute(Integer result) {
      super.onPostExecute(result);
      if (result.intValue() == 0) {
        if (action == 1) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleFinishedLollipop();
          else handleFinishedLegacy();
        } else {
          messageDialog.doDismiss();
        }
      } else        {
//          Toast.makeText(CreateProfileActivity.this, R.string.CreateProfileActivity_problem_setting_profile, Toast.LENGTH_LONG).show();
      }
    }
  }*/

  enum Result {
    SUCCESS, FAILURE_NETWORK
  }

}
