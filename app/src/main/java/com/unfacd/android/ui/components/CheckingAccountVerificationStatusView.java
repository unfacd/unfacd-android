package com.unfacd.android.ui.components;

import android.content.Context;
import android.util.AttributeSet;

import com.unfacd.android.R;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;


public class CheckingAccountVerificationStatusView extends androidx.appcompat.widget.AppCompatButton {

  private long countDown;
  private Listener listener;//AA+

  public CheckingAccountVerificationStatusView(Context context) {
    super(context);
  }

  public CheckingAccountVerificationStatusView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public CheckingAccountVerificationStatusView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  public void startCountDown(long countDown) {
    this.countDown = countDown;
    updateCountDown();
  }

  public void setCallEnabled() {
    setText(R.string.RegistrationActivity_re_check);
    setEnabled(true);
    setAlpha(1.0f);
  }

  private void updateCountDown() {
    if (countDown > 0) {
      setEnabled(false);
      setAlpha(0.5f);

      countDown--;

      long minutesRemaining = countDown / 60;
      long secondsRemaining = countDown - (minutesRemaining * 60);

      setText(getResources().getString(R.string.RegistrationActivity_detecting_email_verification, minutesRemaining, secondsRemaining));
      postDelayed(this::updateCountDown, 1000);
    } else if (countDown == 0) {
      setCallEnabled();

      if (listener != null) listener.onRemaining(this, countDown);//AA+
    }
  }

  //AA+
  @MainThread
  public void setCountDownStateListener(Listener listener) {
    this.listener = listener;
  }

  public interface Listener
  {
    void onRemaining(@NonNull CheckingAccountVerificationStatusView view, long remaining);
  }
  //
}