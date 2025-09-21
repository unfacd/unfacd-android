package com.unfacd.android.ui.components;

import android.os.Build;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

/**
 *
 * http://kunmii.blogspot.com.au/2014/04/how-to-create-easy-android-textview.html
 */
public class TextViewFadeEffectAnimator
{
  TextView blobText;
  public String[] text = new String[] { "" };
  public int position = 0;
  Animation fadeiInAnimationObject;
  Animation textDisplayAnimationObject;
  Animation delayBetweenAnimations;
  Animation fadeOutAnimationObject;
  int fadeEffectDuration;
  int delayDuration;
  int displayFor;

  public TextViewFadeEffectAnimator(TextView textView, String[] textList)
  {
    this(textView,700,1000,2000, textList);
  }

  public TextViewFadeEffectAnimator(TextView textView, int fadeEffectDuration, int delayDuration, int displayLength, String[] textList)
  {
    blobText = textView;
    text = textList;
    this.fadeEffectDuration = fadeEffectDuration;
    this.delayDuration = delayDuration;
    this.displayFor = displayLength;
    InnitialiseAnimation();
  }

  public void startAnimation()
  {
    blobText.startAnimation(fadeOutAnimationObject);
  }

  public void stopAnimation()
  {
    blobText.clearAnimation();
    if (canCancelAnimation()) {
      blobText.animate().cancel();
    }
  }

  /**
   *
   * @return true if the API level supports canceling existing animations via the
   * ViewPropertyAnimator, and false if it does not
   */
  public static boolean canCancelAnimation() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH;
  }

  private void InnitialiseAnimation()
  {
    fadeiInAnimationObject = new AlphaAnimation(0f, 1f);
    fadeiInAnimationObject.setDuration(fadeEffectDuration);
    textDisplayAnimationObject = new AlphaAnimation(1f, 1f);
    textDisplayAnimationObject.setDuration(displayFor);
    delayBetweenAnimations = new AlphaAnimation(0f, 0f);
    delayBetweenAnimations.setDuration(delayDuration);
    fadeOutAnimationObject = new AlphaAnimation(1f, 0f);
    fadeOutAnimationObject.setDuration(fadeEffectDuration);
    fadeiInAnimationObject.setAnimationListener(new Animation.AnimationListener() {

      @Override
      public void onAnimationStart(Animation animation) {
        position++;
        if(position>=text.length)
        {
          position = 0;
        }
        blobText.setText(text[position]);
      }

      @Override
      public void onAnimationRepeat(Animation animation) {}

      @Override
      public void onAnimationEnd(Animation animation) {
        blobText.startAnimation(textDisplayAnimationObject);
      }
    });

    textDisplayAnimationObject.setAnimationListener(new Animation.AnimationListener() {
      @Override
      public void onAnimationStart(Animation animation) {
        // TODO Auto-generated method stub
      }

      @Override
      public void onAnimationRepeat(Animation animation) {
        // TODO Auto-generated method stub
      }

      @Override
      public void onAnimationEnd(Animation animation) {
        // TODO Auto-generated method stub
        blobText.startAnimation(fadeOutAnimationObject);
      }
    });

    fadeOutAnimationObject.setAnimationListener(new Animation.AnimationListener() {
      @Override
      public void onAnimationStart(Animation animation) {
        // TODO Auto-generated method stub
      }

      @Override
      public void onAnimationRepeat(Animation animation) {
        // TODO Auto-generated method stub
      }

      @Override
      public void onAnimationEnd(Animation animation) {
        // TODO Auto-generated method stub
        blobText.startAnimation(delayBetweenAnimations);
      }
    });
    delayBetweenAnimations.setAnimationListener(new Animation.AnimationListener() {
      @Override
      public void onAnimationStart(Animation animation) {
        // TODO Auto-generated method stub
      }

      @Override
      public void onAnimationRepeat(Animation animation) {
        // TODO Auto-generated method stub
      }

      @Override
      public void onAnimationEnd(Animation animation) {
        // TODO Auto-generated method stub
        blobText.startAnimation(fadeiInAnimationObject);
      }
    });
  }

}
