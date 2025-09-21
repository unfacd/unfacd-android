package com.unfacd.android.ui.basenotifaction.anim;

import android.view.View;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import androidx.constraintlayout.widget.ConstraintLayout;

public class NotificationOpenCloseAnimation extends Animation {
  private View animatedView;
  private ConstraintLayout.LayoutParams layoutParams;
  private int marginStart, marginEnd;
  private boolean hideWhenComplete = false;
  private boolean completed = false;

  public NotificationOpenCloseAnimation(View view, int duration) {

    setDuration(duration);
    animatedView = view;
    layoutParams = (ConstraintLayout.LayoutParams)view.getLayoutParams();

    hideWhenComplete = (view.getVisibility() == View.VISIBLE);

    marginStart = layoutParams.bottomMargin;
    marginEnd = (marginStart == 0 ? (0 - view.getHeight()) : 0);

    animatedView.setVisibility(View.VISIBLE);
  }


  @Override
  protected void applyTransformation(float interpolatedTime, Transformation t) {
    super.applyTransformation(interpolatedTime, t);

    if (interpolatedTime < 1.0f) {

      // calculate the new bottom margin based on elapsed animation time
      layoutParams.bottomMargin = marginStart
              + (int) ((marginEnd - marginStart) * interpolatedTime);

      // update the view to make changes visible
      animatedView.requestLayout();

    } else if (!completed) {

      // set final bottom margin
      layoutParams.bottomMargin = marginEnd;

      // update the view to make changes visible
      animatedView.requestLayout();

      // set animated view to "gone"
      if (hideWhenComplete) {
        animatedView.setVisibility(View.GONE);
      }

      completed = true;
    }
  }
}