package com.unfacd.android.ui.basenotifaction;

import com.unfacd.android.R;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.os.Handler;

import com.unfacd.android.ui.basenotifaction.anim.NotificationOpenCloseAnimation;
import com.unfacd.android.ui.basenotifaction.timer.ActionTimer;
import com.unfacd.android.ui.basenotifaction.timer.ActionTimerListener;

/**
 * based on https://github.com/jeffcardillo/SlideNotification
 */
public class BaseNotificationActivity extends AppCompatActivity implements ActionTimerListener {

  private ActionTimer notificationTimer = new ActionTimer();
  private LinearLayout notificationView = null;
  private TextView notificationLabel = null;
  private ImageButton notificationBarClose = null;

  @Override
  protected void onCreate(Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);

  }

  @Override
  protected void onStart() {
    super.onStart();
    // load these views here so that subclass has already set the layout in its own "onCreate"
    notificationView      = findViewById(R.id.notification_bar);
    notificationLabel     = findViewById(R.id.notification_label);
    notificationBarClose  = findViewById(R.id.notification_close_button);
    notificationTimer.addListener(this);
    if (notificationBarClose != null) {
      notificationBarClose.setOnClickListener((arg0) ->  hideNotification());
    }
  }

  @Override
  protected void onStop() {
    super.onStop();

    notificationTimer.removeListener(this);
  }

  public void postNotification(String message, boolean closeable) {
    if (notificationView != null && notificationLabel != null) {
      notificationLabel.setText(message);

      if (closeable) {
        notificationBarClose.setVisibility(View.VISIBLE);

        if (notificationTimer != null)
          notificationTimer.stopTimer();
      } else {
        notificationBarClose.setVisibility(View.GONE);
      }

      if (notificationView.getVisibility() != View.VISIBLE) {
        NotificationOpenCloseAnimation anim = new NotificationOpenCloseAnimation(notificationView, 350);
        notificationView.startAnimation(anim);
      }
    }
  }

  public void postTimedNotification(String message) {
    if (notificationView != null && notificationLabel != null) {
      notificationTimer.startTimer();

      postNotification(message, false);
    }
  }

  public void hideNotification() {
    if (notificationView != null && notificationLabel != null) {
      if (notificationView.getVisibility() == View.VISIBLE) {
        notificationBarClose.setVisibility(View.GONE);

        NotificationOpenCloseAnimation anim = new NotificationOpenCloseAnimation(notificationView, 350);
        notificationView.startAnimation(anim);
      }
    }
  }

  @Override
  public void actionTimerCompleted() {
    Handler mainHandler = new Handler(this.getMainLooper());
    Runnable post = () -> {
        if(notificationView.getVisibility() == View.VISIBLE) hideNotification();
    };

    mainHandler.post(post);
  }
}