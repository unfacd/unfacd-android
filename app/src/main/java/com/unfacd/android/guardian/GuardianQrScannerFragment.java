package com.unfacd.android.guardian;

import com.dd.CircularProgressButton;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import android.animation.Animator;
import android.annotation.TargetApi;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.thoughtcrime.securesms.components.camera.CameraView;
import org.thoughtcrime.securesms.qr.ScanListener;
import org.thoughtcrime.securesms.qr.ScanningThread;
import org.thoughtcrime.securesms.util.ViewUtil;

public class GuardianQrScannerFragment extends Fragment {

  private ViewGroup      container;
  private LinearLayout   overlay;
  private ImageView      devicesImage;
  private CameraView     scannerView;
  private ScanningThread scanningThread;
  private ScanListener   scanListener;

  private CircularProgressButton finishButton;
  private View                    reveal;

  private int countDown = GuardianRequestQrView.NonceValidityTime;

  Handler handler = new Handler(Looper.getMainLooper());
  Runnable ticker = new Runnable() {
    @Override
    public void run() {
      updateCountDown();
      handler.postDelayed(ticker, 1000);

    }
  };

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup viewGroup, Bundle bundle) {
    this.container    = ViewUtil.inflate(inflater, viewGroup, R.layout.guardian_qr_scanner);// based off R.layout.device_add_fragment
    this.overlay      = this.container.findViewById(R.id.overlay);
    this.scannerView  = this.container.findViewById(R.id.scanner);
    this.devicesImage = this.container.findViewById(R.id.devices);

    this.finishButton = this.container.findViewById(R.id.finish_button);
    this.reveal = this.container.findViewById(R.id.reveal);

    this.finishButton.setText(R.string.GuardianNotificationBuilder_guardian_request_cancel);
    this.finishButton.setOnClickListener(view -> {
      //dialog dismiss happens on animation end callback. temporarily below
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleFinishedLollipop();
      else handleFinishedLegacy();
    });

    if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
      this.overlay.setOrientation(LinearLayout.HORIZONTAL);
    } else {
      this.overlay.setOrientation(LinearLayout.VERTICAL);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      this.container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom)
        {
          v.removeOnLayoutChangeListener(this);

          Animator reveal = ViewAnimationUtils.createCircularReveal(v, right, bottom, 0, (int) Math.hypot(right, bottom));
          reveal.setInterpolator(new DecelerateInterpolator(2f));
          reveal.setDuration(800);
          reveal.start();
        }
      });
    }

    return this.container;
  }

  @Override
  public void onResume() {
    super.onResume();
    this.scanningThread = new ScanningThread();
    this.scanningThread.setScanListener(scanListener);
    this.scannerView.onResume();
    this.scannerView.setPreviewCallback(scanningThread);
    this.scanningThread.start();

    ticker.run();
  }

  @Override
  public void onPause() {
    super.onPause();
    this.scannerView.onPause();
    this.scanningThread.stopScanning();
  }

  @Override
  public void onConfigurationChanged(Configuration newConfiguration) {
    super.onConfigurationChanged(newConfiguration);

    this.scannerView.onPause();

    if (newConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
      overlay.setOrientation(LinearLayout.HORIZONTAL);
    } else {
      overlay.setOrientation(LinearLayout.VERTICAL);
    }

    this.scannerView.onResume();
    this.scannerView.setPreviewCallback(scanningThread);
  }

  public ImageView getDevicesImage() {
    return devicesImage;
  }

  public void setScanListener(ScanListener scanListener) {
    this.scanListener = scanListener;

    if (this.scanningThread != null) {
      this.scanningThread.setScanListener(scanListener);
    }
  }

  private void handleFinishedLegacy() {
    finishButton.setProgress(0);
    getActivity().finish();
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  private void handleFinishedLollipop() {
    int[] finishButtonLocation = new int[2];
    int[] revealLocation       = new int[2];

    finishButton.getLocationInWindow(finishButtonLocation);
    reveal.getLocationInWindow(revealLocation);

    int finishX = finishButtonLocation[0] - revealLocation[0];
    int finishY = finishButtonLocation[1] - revealLocation[1];

    finishX += finishButton.getWidth() / 2;
    finishY += finishButton.getHeight() / 2;

    Animator animation = ViewAnimationUtils.createCircularReveal(reveal, finishX, finishY, 0f, (float) Math.max(reveal.getWidth(), reveal.getHeight()));
    animation.setDuration(500);
    animation.addListener(new Animator.AnimatorListener() {
      @Override
      public void onAnimationStart(Animator animation) {}

      @Override
      public void onAnimationEnd(Animator animation) {
        finishButton.setProgress(0);
        ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
        getActivity().finish();
      }

      @Override
      public void onAnimationCancel(Animator animation) {}
      @Override
      public void onAnimationRepeat(Animator animation) {}
    });

    reveal.setVisibility(View.VISIBLE);
    animation.start();
  }

  private void updateCountDown ()
  {
    if (countDown > 0) {
      countDown--;

      int minutesRemaining = countDown / 60;
      int secondsRemaining = countDown - (minutesRemaining * 60);

      finishButton.setProgress((int)Math.round(Math.floor(((double)countDown / GuardianRequestQrView.NonceValidityTime)*100.0)));
    } else if (countDown <= 0) {
      finishButton.setProgress(100);
      finishButton.setCompleteText("EXPIRED");
      countDown = 0;
    }
  }
}
