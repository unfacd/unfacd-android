package com.unfacd.android.ui.components;

import com.unfacd.android.R;

import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import android.util.AttributeSet;


public class CheckingAccountVerificationStatusView extends androidx.appcompat.widget.AppCompatButton {

  private int countDown;
  private CountdownStateListener listener;//

  public CheckingAccountVerificationStatusView(Context context) {
    super(context);
  }

  public CheckingAccountVerificationStatusView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public CheckingAccountVerificationStatusView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  public void startCountDown(int countDown) {
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

      int minutesRemaining = countDown / 60;
      int secondsRemaining = countDown - (minutesRemaining * 60);

      setText(getResources().getString(R.string.RegistrationActivity_detecting_email_verification, minutesRemaining, secondsRemaining));
      postDelayed(this::updateCountDown, 1000);
    } else if (countDown == 0) {
      setCallEnabled();

      if (listener!=null) listener.onCountdownReached();//
    }
  }

  //
  @MainThread
  public void setCountDownStateListener (CountdownStateListener listener) {
    this.listener = listener;
  }

  public interface CountdownStateListener
  {
    void onCountdownReached();
  }
  //
}