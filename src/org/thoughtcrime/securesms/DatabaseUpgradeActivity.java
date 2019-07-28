/**
 * Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.securesms;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import org.thoughtcrime.securesms.logging.Log;
import android.view.View;
import android.widget.ProgressBar;

import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase.Reader;
import org.thoughtcrime.securesms.database.PushDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.jobs.PushDecryptJob;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.util.FileUtils;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.VersionTracker;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class DatabaseUpgradeActivity extends BaseActivity {
  private static final String TAG = DatabaseUpgradeActivity.class.getSimpleName();

  public static final int STICKY_RECIPIENTS_VERSION               = 7;
  public static final int NO_MORE_CANONICAL_DB_VERSION            = 8;
  public static final int PROFILES                                =  9;
  public static final int SCREENSHOTS                             = 10;
  public static final int PERSISTENT_BLOBS                        = 11;
  public static final int SQLCIPHER                               = 11;
  public static final int INTERNALIZE_CONTACTS                    = 12;
  public static final int FULL_TEXT_SEARCH                        = 13;
  public static final int IMAGE_CACHE_CLEANUP                     = 19;

  private static final SortedSet<Integer> UPGRADE_VERSIONS = new TreeSet<Integer>() {{
    add(STICKY_RECIPIENTS_VERSION);
    add(NO_MORE_CANONICAL_DB_VERSION);
    add(SCREENSHOTS);
    add(INTERNALIZE_CONTACTS);
    add(PERSISTENT_BLOBS);
    add(SQLCIPHER);
    add(FULL_TEXT_SEARCH);
    add(IMAGE_CACHE_CLEANUP);
  }};

  private MasterSecret masterSecret;

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    this.masterSecret = KeyCachingService.getMasterSecret(this);

    if (needsUpgradeTask()) {
      Log.w("DatabaseUpgradeActivity", "Upgrading...");
      setContentView(R.layout.database_upgrade_activity);

      ProgressBar indeterminateProgress = findViewById(R.id.indeterminate_progress);
      ProgressBar determinateProgress   = findViewById(R.id.determinate_progress);

      new DatabaseUpgradeTask(indeterminateProgress, determinateProgress)
              .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, VersionTracker.getLastSeenVersion(this));
    } else {
      VersionTracker.updateLastSeenVersion(this);
      updateNotifications(this);
      startActivity((Intent)getIntent().getParcelableExtra("next_intent"));
      finish();
    }
  }

  private boolean needsUpgradeTask() {
    int currentVersionCode = Util.getCanonicalVersionCode();
    int lastSeenVersion    = VersionTracker.getLastSeenVersion(this);

    Log.w("DatabaseUpgradeActivity", "LastSeenVersion: " + lastSeenVersion);

    if (lastSeenVersion >= currentVersionCode)
      return false;

    for (int version : UPGRADE_VERSIONS) {
      Log.w("DatabaseUpgradeActivity", "Comparing: " + version);
      if (lastSeenVersion < version)
        return true;
    }

    return false;
  }

  public static boolean isUpdate(Context context) {
    int currentVersionCode  = Util.getCanonicalVersionCode();
    int previousVersionCode = VersionTracker.getLastSeenVersion(context);

    return previousVersionCode < currentVersionCode;
  }

  @SuppressLint("StaticFieldLeak")
  private void updateNotifications(final Context context) {
    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        MessageNotifier.updateNotification(context);
        return null;
      }
   }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  public interface DatabaseUpgradeListener {
    public void setProgress(int progress, int total);
  }

  @SuppressLint("StaticFieldLeak")
  private class DatabaseUpgradeTask extends AsyncTask<Integer, Double, Void>
          implements DatabaseUpgradeListener
  {

    private final ProgressBar indeterminateProgress;
    private final ProgressBar determinateProgress;

    DatabaseUpgradeTask(ProgressBar indeterminateProgress, ProgressBar determinateProgress) {
      this.indeterminateProgress = indeterminateProgress;
      this.determinateProgress   = determinateProgress;
    }

    @Override
    protected Void doInBackground(Integer... params) {
      Context context = DatabaseUpgradeActivity.this.getApplicationContext();

      Log.w("DatabaseUpgradeActivity", "Running background upgrade..");
      DatabaseFactory.getInstance(DatabaseUpgradeActivity.this)
              .onApplicationLevelUpgrade(context, masterSecret, params[0], this);

      if (params[0] < PROFILES) {
        ApplicationContext.getInstance(getApplicationContext())
                                   .getJobManager()
                                    .add(new DirectoryRefreshJob(false));
        }

      if (params[0] < SCREENSHOTS) {
          TextSecurePreferences.setScreenSecurityEnabled(getApplicationContext(), true);
        }

      if (params[0] < PERSISTENT_BLOBS) {
        File externalDir = context.getExternalFilesDir(null);

        if (externalDir != null && externalDir.isDirectory() && externalDir.exists()) {
            for (File blob : externalDir.listFiles()) {
                if (blob.exists() && blob.isFile()) blob.delete();
              }
          }
      }

      if (params[0] < INTERNALIZE_CONTACTS) {
        if (TextSecurePreferences.isPushRegistered(getApplicationContext())) {
            TextSecurePreferences.setHasSuccessfullyRetrievedDirectory(getApplicationContext(), true);
          }
      }

      if (params[0] < SQLCIPHER) {
        scheduleMessagesInPushDatabase(context);
      }

      if (params[0] < IMAGE_CACHE_CLEANUP) {
        try {
          FileUtils.deleteDirectoryContents(context.getExternalCacheDir());
          GlideApp.get(context).clearDiskCache();
        } catch (IOException e) {
          Log.w(TAG, e);
        }
      }

      return null;
    }

    private void schedulePendingIncomingParts(Context context) {
      final AttachmentDatabase       attachmentDb       = DatabaseFactory.getAttachmentDatabase(context);
      final MmsDatabase              mmsDb              = DatabaseFactory.getMmsDatabase(context);
      final List<DatabaseAttachment> pendingAttachments = DatabaseFactory.getAttachmentDatabase(context).getPendingAttachments();

      Log.w(TAG, pendingAttachments.size() + " pending parts.");
      for (DatabaseAttachment attachment : pendingAttachments) {
        final Reader        reader = mmsDb.readerFor(mmsDb.getMessage(attachment.getMmsId()));
        final MessageRecord record = reader.getNext();

        if (attachment.hasData()) {
          Log.w(TAG, "corrected a pending media part " + attachment.getAttachmentId() + "that already had data.");
          attachmentDb.setTransferState(attachment.getMmsId(), attachment.getAttachmentId(), AttachmentDatabase.TRANSFER_PROGRESS_DONE);
        } else if (record != null && !record.isOutgoing() && record.isPush()) {
          Log.w(TAG, "queuing new attachment download job for incoming push part " + attachment.getAttachmentId() + ".");
          ApplicationContext.getInstance(context)
                  .getJobManager()
                  .add(new AttachmentDownloadJob(attachment.getMmsId(), attachment.getAttachmentId(), false));
        }
        reader.close();
      }
    }

    private void scheduleMessagesInPushDatabase(Context context) {
      PushDatabase pushDatabase = DatabaseFactory.getPushDatabase(context);
      Cursor       pushReader   = null;

      try {
        pushReader = pushDatabase.getPending();

        while (pushReader != null && pushReader.moveToNext()) {
          ApplicationContext.getInstance(getApplicationContext())
                  .getJobManager()
                  .add(new PushDecryptJob(getApplicationContext(),
                                          pushReader.getLong(pushReader.getColumnIndexOrThrow(PushDatabase.ID))));
        }
      } finally {
        if (pushReader != null)
          pushReader.close();
      }
    }

    @Override
    protected void onProgressUpdate(Double... update) {
      indeterminateProgress.setVisibility(View.GONE);
      determinateProgress.setVisibility(View.VISIBLE);

      double scaler = update[0];
      determinateProgress.setProgress((int)Math.floor(determinateProgress.getMax() * scaler));
    }

    @Override
    protected void onPostExecute(Void result) {
      VersionTracker.updateLastSeenVersion(DatabaseUpgradeActivity.this);
      updateNotifications(DatabaseUpgradeActivity.this);

      startActivity((Intent)getIntent().getParcelableExtra("next_intent"));
      finish();
    }

    @Override
    public void setProgress(int progress, int total) {
      publishProgress(((double)progress / (double)total));
    }
  }

}
