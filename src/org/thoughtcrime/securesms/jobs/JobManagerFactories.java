package org.thoughtcrime.securesms.jobs;

import android.app.Application;
import androidx.annotation.NonNull;

import com.unfacd.android.jobs.UnfacdIntroContactJob;
import com.unfacd.android.jobs.LocationRefreshJob;
import com.unfacd.android.jobs.MapableAvatarDownloadJob;
import com.unfacd.android.jobs.MessageCommandEndSessionJob;
import com.unfacd.android.jobs.PresenceReceiverJob;
import com.unfacd.android.jobs.ProfileAvatarDownloadJob;
import com.unfacd.android.jobs.RegistrationStatusVerifierJob;
import com.unfacd.android.jobs.ReloadGroupJob;
import com.unfacd.android.jobs.ResetGroupsJob;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob;

import org.thoughtcrime.securesms.jobmanager.Constraint;
import org.thoughtcrime.securesms.jobmanager.ConstraintObserver;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.CellServiceConstraint;
import org.thoughtcrime.securesms.jobmanager.impl.CellServiceConstraintObserver;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraintObserver;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkOrCellServiceConstraint;
import org.thoughtcrime.securesms.jobmanager.impl.SqlCipherMigrationConstraint;
import org.thoughtcrime.securesms.jobmanager.impl.SqlCipherMigrationConstraintObserver;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JobManagerFactories {

  public static Map<String, Job.Factory> getJobFactories(@NonNull Application application) {
    return new HashMap<String, Job.Factory>() {{
      put(AttachmentCopyJob.KEY,                     new AttachmentCopyJob.Factory());
      put(AttachmentDownloadJob.KEY,                 new AttachmentDownloadJob.Factory());
      put(AttachmentUploadJob.KEY,                   new AttachmentUploadJob.Factory());
      put(AvatarDownloadJob.KEY,                     new AvatarDownloadJob.Factory());
      put(CleanPreKeysJob.KEY,                       new CleanPreKeysJob.Factory());
      put(CreateSignedPreKeyJob.KEY,                 new CreateSignedPreKeyJob.Factory());
      put(DirectoryRefreshJob.KEY,                   new DirectoryRefreshJob.Factory(application));
      put(FcmRefreshJob.KEY,                         new FcmRefreshJob.Factory());
      put(LocalBackupJob.KEY,                        new LocalBackupJob.Factory());
      put(MmsDownloadJob.KEY,                        new MmsDownloadJob.Factory());
      put(MmsReceiveJob.KEY,                         new MmsReceiveJob.Factory());
      put(MmsSendJob.KEY,                            new MmsSendJob.Factory());
      put(MultiDeviceBlockedUpdateJob.KEY,           new MultiDeviceBlockedUpdateJob.Factory());
      put(MultiDeviceConfigurationUpdateJob.KEY,     new MultiDeviceConfigurationUpdateJob.Factory());
      put(MultiDeviceContactUpdateJob.KEY,           new MultiDeviceContactUpdateJob.Factory());
      put(MultiDeviceGroupUpdateJob.KEY,             new MultiDeviceGroupUpdateJob.Factory());
      put(MultiDeviceProfileKeyUpdateJob.KEY,        new MultiDeviceProfileKeyUpdateJob.Factory());
      put(MultiDeviceReadUpdateJob.KEY,              new MultiDeviceReadUpdateJob.Factory());
      put(MultiDeviceRevealUpdateJob.KEY,            new MultiDeviceRevealUpdateJob.Factory());
      put(MultiDeviceStickerPackOperationJob.KEY,    new MultiDeviceStickerPackOperationJob.Factory());
      put(MultiDeviceStickerPackSyncJob.KEY,         new MultiDeviceStickerPackSyncJob.Factory());
      put(MultiDeviceVerifiedUpdateJob.KEY,          new MultiDeviceVerifiedUpdateJob.Factory());
      put(PushContentReceiveJob.KEY,                 new PushContentReceiveJob.Factory());
      put(PushDecryptJob.KEY,                        new PushDecryptJob.Factory());
      put(PushGroupSendJob.KEY,                      new PushGroupSendJob.Factory());
      put(PushGroupUpdateJob.KEY,                    new PushGroupUpdateJob.Factory());
      put(PushMediaSendJob.KEY,                      new PushMediaSendJob.Factory());
      put(PushNotificationReceiveJob.KEY,            new PushNotificationReceiveJob.Factory());
      put(PushTextSendJob.KEY,                       new PushTextSendJob.Factory());
      put(RefreshAttributesJob.KEY,                  new RefreshAttributesJob.Factory());
      put(RefreshPreKeysJob.KEY,                     new RefreshPreKeysJob.Factory());
      put(RefreshUnidentifiedDeliveryAbilityJob.KEY, new RefreshUnidentifiedDeliveryAbilityJob.Factory());
      put(RequestGroupInfoJob.KEY,                   new RequestGroupInfoJob.Factory());
      put(RetrieveProfileAvatarJob.KEY,              new RetrieveProfileAvatarJob.Factory(application));
      put(RetrieveProfileJob.KEY,                    new RetrieveProfileJob.Factory(application));
      put(RotateCertificateJob.KEY,                  new RotateCertificateJob.Factory());
      put(RotateProfileKeyJob.KEY,                   new RotateProfileKeyJob.Factory());
      put(RotateSignedPreKeyJob.KEY,                 new RotateSignedPreKeyJob.Factory());
      put(SendDeliveryReceiptJob.KEY,                new SendDeliveryReceiptJob.Factory());
      put(SendReadReceiptJob.KEY,                    new SendReadReceiptJob.Factory());
      put(ServiceOutageDetectionJob.KEY,             new ServiceOutageDetectionJob.Factory());
      put(SmsReceiveJob.KEY,                         new SmsReceiveJob.Factory());
      put(SmsSendJob.KEY,                            new SmsSendJob.Factory());
      put(SmsSentJob.KEY,                            new SmsSentJob.Factory());
      put(StickerDownloadJob.KEY,                    new StickerDownloadJob.Factory());
      put(StickerPackDownloadJob.KEY,                new StickerPackDownloadJob.Factory());
      put(TrimThreadJob.KEY,                         new TrimThreadJob.Factory());
      put(TypingSendJob.KEY,                         new TypingSendJob.Factory());
      put(UpdateApkJob.KEY,                          new UpdateApkJob.Factory());

      //
      put(LocationRefreshJob.KEY,                     new LocationRefreshJob.Factory());
      put(MapableAvatarDownloadJob.KEY,               new MapableAvatarDownloadJob.Factory());
      put(MessageCommandEndSessionJob.KEY,            new MessageCommandEndSessionJob.Factory());
      put(PresenceReceiverJob.KEY,                    new PresenceReceiverJob.Factory(application));
      put(ProfileAvatarDownloadJob.KEY,               new ProfileAvatarDownloadJob.Factory());
      put(RegistrationStatusVerifierJob.KEY,          new RegistrationStatusVerifierJob.Factory());
      put(ReloadGroupJob.KEY,                         new ReloadGroupJob.Factory());
      put(ResetGroupsJob.KEY,                         new ResetGroupsJob.Factory());
      put(UfsrvUserCommandProfileJob.KEY,             new UfsrvUserCommandProfileJob.Factory());
      put(UnfacdIntroContactJob.KEY,                  new UnfacdIntroContactJob.Factory());
    }};
  }

  public static Map<String, Constraint.Factory> getConstraintFactories(@NonNull Application application) {
    return new HashMap<String, Constraint.Factory>() {{
      put(CellServiceConstraint.KEY,          new CellServiceConstraint.Factory(application));
      put(NetworkConstraint.KEY,              new NetworkConstraint.Factory(application));
      put(NetworkOrCellServiceConstraint.KEY, new NetworkOrCellServiceConstraint.Factory(application));
      put(SqlCipherMigrationConstraint.KEY,   new SqlCipherMigrationConstraint.Factory(application));
    }};
  }

  public static List<ConstraintObserver> getConstraintObservers(@NonNull Application application) {
    return Arrays.asList(CellServiceConstraintObserver.getInstance(application),
                         new NetworkConstraintObserver(application),
                         new SqlCipherMigrationConstraintObserver());
  }
}