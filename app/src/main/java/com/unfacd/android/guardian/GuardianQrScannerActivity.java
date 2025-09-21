package com.unfacd.android.guardian;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.MenuItem;
import android.widget.Toast;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.jobs.GuardianRequestJob;
import com.unfacd.android.ufsrvcmd.events.AppEventGuardianCommand;
import com.unfacd.android.utils.GuardianNonceHelper;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.signal.core.util.ThreadUtil;
import org.thoughtcrime.securesms.PassphraseRequiredActivity;
import org.thoughtcrime.securesms.conversation.ConversationParentFragment;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.qr.ScanListener;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicTheme;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

public class GuardianQrScannerActivity  extends PassphraseRequiredActivity
        implements  ScanListener
{
  
  private static final String TAG = Log.tag(GuardianQrScannerActivity.class);

  public static final String RECIPIENT_EXTRA        = "recipient_id";
  public static final String GROUP_FID_EXTRA         = "group_fid";
  public static final String CHALLENGE_EXTRA         = "challenge";

  private final DynamicTheme dynamicTheme    = new DynamicTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private GuardianQrScannerFragment scannerFragment;

  private String nonceKey;
  private Long fid;
  private String guardedAddress;

  @Override
  public void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  public void onCreate(Bundle bundle, boolean ready) {

    setContentView(R.layout.uf_generic_container);
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayUseLogoEnabled(false);

    nonceKey = getIntent().getStringExtra(CHALLENGE_EXTRA);
    fid = getIntent().getLongExtra(GROUP_FID_EXTRA, 0);
    guardedAddress = getIntent().getStringExtra(RECIPIENT_EXTRA);

    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setTitle(R.string.AndroidManifest__guardian_link);

    this.scannerFragment  = new GuardianQrScannerFragment();
    this.scannerFragment.setScanListener(this);

    initFragment(android.R.id.content, scannerFragment, dynamicLanguage.getCurrentLocale());
    overridePendingTransition(R.anim.slide_from_end, R.anim.slide_to_start);
  }

  @Override
  protected void onPause() {
    if (isFinishing()) {
      overridePendingTransition(R.anim.slide_from_start, R.anim.slide_to_end);
    }
    super.onPause();

    ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);

    ApplicationContext.getInstance().getUfsrvcmdEvents().register(this);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case android.R.id.home: finish(); return true;
    }

    return false;
  }

 private void launchScanner () {
    Permissions.with(this)
            .request(Manifest.permission.CAMERA)
            .ifNecessary()
            .withPermanentDenialDialog(getString(R.string.DeviceActivity_signal_needs_the_camera_permission_in_order_to_scan_a_qr_code))
            .onAllGranted(() -> {
              getSupportFragmentManager().beginTransaction()
                      .replace(android.R.id.content, scannerFragment)
                      .addToBackStack(null)
                      .commitAllowingStateLoss();
            })
            .onAnyDenied(() -> Toast.makeText(this, R.string.DeviceActivity_unable_to_scan_a_qr_code_without_the_camera_permission, Toast.LENGTH_LONG).show())
            .execute();
  }

  @Override
  public void onQrDataFound(final String data) {
    ThreadUtil.runOnMain(() -> {
      Uri uri = Uri.parse(data);
      GuardianNonceHelper.SealedData sealedData = GuardianNonceHelper.SealedData.fromString(data);
      try {
        byte decryptedBytes[] = GuardianNonceHelper.unseal(sealedData, GuardianNonceHelper.SealedData.deserialiseKey(nonceKey));
        Log.e(TAG, String.format("onQrDataFound: NONCE: '%s'", new String(decryptedBytes)));

        sendGuardianRequest (GuardianQrScannerActivity.this, fid, new String(decryptedBytes), guardedAddress);
      } catch (java.security.InvalidKeyException x) {
        finish();
      }
    });
  }

  private void finishLiniking(int status)
  {
    Intent intentData = new Intent();
    intentData.putExtra(CHALLENGE_EXTRA, status);
    setResult(ConversationParentFragment.GUARDIAN_NONCE, intentData);
    finish();
  }

  private void sendGuardianRequest (Context context, long fid, String nonce, String recipientAddress) {
    JobManager jobManager = ApplicationDependencies.getJobManager();
    jobManager.add(new GuardianRequestJob(fid, nonce, recipientAddress));
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Subscribe(sticky = false, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventGuardianCommand event)
  {
    if (event.isCommandLink()) {
      if (event.isCommandAccepted()) {
        ((Vibrator)getSystemService(Context.VIBRATOR_SERVICE)).vibrate(50);
        finishLiniking(1);
      } else {
        finishLiniking(0);
      }
    }
  }
}
