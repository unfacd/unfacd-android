package com.unfacd.android.guardian;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.dd.CircularProgressButton;
import com.kongzue.dialogx.dialogs.MessageDialog;
import com.kongzue.dialogx.interfaces.DialogLifecycleCallback;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;
import com.unfacd.android.ufsrvcmd.events.AppEventGuardianCommand;
import com.unfacd.android.ui.components.MessageDialogCloseListener;

import org.signal.core.util.StreamUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.PassphraseRequiredActivity;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.qr.QrCode;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.io.IOException;

import androidx.annotation.RequiresApi;

@SuppressLint("StaticFieldLeak")
public class GuardianRequestQrView extends LinearLayout implements PassphraseRequiredActivity.ChildViewEventListener
{
  private Context                 context;
  private CircularProgressButton  finishButton;
  private View                    reveal;
  private ImageView               qr;
  private TextView                description;

  private MessageDialog           messageDialog;
  private MessageDialogCloseListener closeListener = null;
  private GuardianDescriptor descriptorProvided = null;

  protected static final int NonceValidityTime = 60; //180 seconds
  private int countDown = NonceValidityTime;

  private static final String TAG = Log.tag(GuardianRequestQrView.class);

  public GuardianRequestQrView (Context context) {
    this(context, null);
  }

  public GuardianRequestQrView (Context context, AttributeSet attrs) {
    super(context, attrs);
    this.context = context;
    initialize(context);
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  public GuardianRequestQrView (Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize(context);
  }

  public GuardianRequestQrView setContainerContext (MessageDialog messageDialog)
  {
    this.messageDialog = messageDialog;

    return this;
  }

  public GuardianRequestQrView setDescriptor (GuardianDescriptor descriptor)
  {
    this.descriptorProvided = descriptor;

    return this;
  }

  public GuardianRequestQrView setCloseListener (MessageDialogCloseListener closeListener)
  {
    this.closeListener = closeListener;

    return this;
  }

  public void finalise ()
  {
    if (this.messageDialog == null) {
      throw new IllegalStateException ("ContainerContext not set");
    }

    messageDialog.setDialogLifecycleCallback(new DialogLifecycleCallback<MessageDialog>() {
                                              @Override
                                              public void onDismiss(MessageDialog dialog) {
                                                handleCloseListener(0);
                                              }
                                            });

    initializeResources();
  }

  private void initialize(Context context) {
    LayoutInflater.from(context).inflate(R.layout.guardian_request_qr_view, this, true);

  }

  private Bitmap generateQr (String challenge)
  {
    Bitmap qrCodeBitmap = QrCode.create(challenge);
    return qrCodeBitmap;
  }

  private void initializeResources()
  {
    this.finishButton = findViewById(R.id.finish_button);
    this.reveal = findViewById(R.id.reveal);

    this.description = findViewById(R.id.description_text);
    description.setText(context.getString(R.string.GuardianRequestDialog_x_must_now_scan_the_QR_code_t, descriptorProvided.getRecipient().getDisplayName()));

    this.qr = findViewById(R.id.qr_code);
    this.qr.setImageBitmap(generateQr(descriptorProvided.getChallenge()));
    this.qr.setVisibility(VISIBLE);

    //external request mode
    initializeProfileAvatar(descriptorProvided.getRecipient());

    this.finishButton.setText(R.string.GuardianNotificationBuilder_guardian_request_cancel);
    this.finishButton.setOnClickListener(view -> {
      //dialog dismiss happens on animation end callback. temporarily below
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleFinishedLollipop();
      else handleFinishedLegacy();
    });

    scrollToBottomIfNecessary();
    updateCountDown();
  }

  private void updateCountDown ()
  {
    if (countDown > 0) {
      countDown--;

      int minutesRemaining = countDown / 60;
      int secondsRemaining = countDown - (minutesRemaining * 60);

      finishButton.setProgress((int)Math.round(Math.floor(((double)countDown / NonceValidityTime)*100.0)));
      postDelayed(this::updateCountDown, 1000);
    } else if (countDown <= 0) {
      finishButton.setProgress(100);
      finishButton.setCompleteText("EXPIRED");
      countDown = 0;

      blurQr(1000);
    }
  }

  private void blurQr (long duration)
  {
    qr.animate()
            .alpha(0.05f)
            .setDuration(duration)
            .setListener(new AnimatorListenerAdapter() {
              @Override
              public void onAnimationEnd(Animator animation) {
//                  qr.setVisibility(INVISIBLE);
              }
            });
  }

  private void initializeProfileAvatar (Recipient recipient) {
    Log.d(TAG, String.format("initializeProfileAvatar: Loading avatar file: '%s'", recipient.getUfsrvUid()));

    if (AvatarHelper.hasAvatar(context, recipient.getId()) && AvatarHelper.getAvatarLength(context, recipient.getId()) > 0) {
      new AsyncTask<Void, Void, byte[]>() {
        byte[] avatarBytes;

        @Override
        protected byte[] doInBackground(Void... params) {
          try {
            return StreamUtil.readFully(AvatarHelper.getAvatar(context, recipient.getId()));
          } catch (IOException e) {
            Log.w(TAG, e);
            return null;
          }
        }

        @Override
        protected void onPostExecute(byte[] result) {
          if (result != null) {
            avatarBytes = result;
            GlideApp.with(context)
                    .load(result)
                    .circleCrop()
                    .into(qr);
          }
        }
      }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }

  private void scrollToBottomIfNecessary()
  {
    ScrollView scrollView = findViewById(R.id.scroll_view);
    scrollView.postDelayed(() -> {
        View lastChild = scrollView.getChildAt(scrollView.getChildCount() - 1);
        int bottom = lastChild.getBottom() + scrollView.getPaddingBottom();
        int sy = scrollView.getScrollY();
        int sh = scrollView.getHeight();
        int delta = bottom - (sy + sh);

        scrollView.smoothScrollBy(0, delta);
      },2000);

  }

  private void handleFinishedLegacy() {
    finishButton.setProgress(0);
    messageDialog.dismiss();
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
        messageDialog.dismiss();
      }

      @Override
      public void onAnimationCancel(Animator animation) {}
      @Override
      public void onAnimationRepeat(Animator animation) {}
    });

    reveal.setVisibility(View.VISIBLE);
    animation.start();
  }

  private void handleCloseListener (int button)
  {
    if (this.closeListener != null) {
      closeListener.onMessageDialogClose(button);
    }
  }

  private void finishWithSuccess ()
  {
    finishButton.setProgress(100);
    countDown = 0;

    blurQr(1000);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleFinishedLollipop();
    else handleFinishedLegacy();
  }

  @Override
  public void onUfsrvCommandEvent (UfsrvCommandEvent eventIn)
  {
    AppEventGuardianCommand event = (AppEventGuardianCommand) eventIn;
    if (event.isCommandLink()) {
      if (event.isCommandAccepted()) {
      }
      else if (event.isCommandSynced()) {
        finishWithSuccess();
      }
    }
  }
}
