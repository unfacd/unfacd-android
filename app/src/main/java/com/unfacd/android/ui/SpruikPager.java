package com.unfacd.android.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.unfacd.android.R;
import com.unfacd.android.utils.Utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

public class SpruikPager extends FrameLayout
{
  ViewPager viewPager;
  ImageView topImage1;
  ImageView topImage2;
  ViewGroup bottomPages;
  private int lastPage = 0;
  private boolean justCreated = true;
  private boolean startPressed = false;
  private int[] icons;
  private int[] titles;
  private int[] messages;

  public SpruikPager (Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    LayoutInflater.from(context).inflate(R.layout.uf_intro_layout, this, true);

    TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.SpruikView, 0, 0);

    try {
//      subtitle = a.getString(R.styleable.SpruikView_customViewSubtitle);
    } finally {
      a.recycle();
    }

    initResources(context);
    initPager(context);
  }

  void initResources(@NonNull Context context)
  {
    if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL)  {
      icons = new int[]{
              R.drawable.intro_privacy,
              R.drawable.intro_brain_outline,
              R.drawable.intro_group_management,
              R.drawable.intro_message_confirmation,
              R.drawable.intro_encryption,
              R.drawable.intro_location,
              R.drawable.unfacd_240x213
      };
      titles = new int[]{
              R.string.Page7Title,
              R.string.Page6Title,
              R.string.Page5Title,
              R.string.Page4Title,
              R.string.Page3Title,
              R.string.Page2Title,
              R.string.Page1Title
      };
      messages = new int[]{
              R.string.Page7Message,
              R.string.Page6Message,
              R.string.Page5Message,
              R.string.Page4Message,
              R.string.Page3Message,
              R.string.Page2Message,
              R.string.Page1Message
      };
    } else {
      icons = new int[]{
              R.drawable.unfacd_240x213,
              R.drawable.intro_location,//twotone_place_24px,
              R.drawable.intro_encryption,//twotone_vpn_key_24px,
              R.drawable.intro_message_confirmation,//twotone_how_to_reg_24px,
              R.drawable.intro_group_management,//twotone_group_24px,
              R.drawable.intro_brain_outline,//twotone_scatter_plot_24px,
              R.drawable.intro_privacy,//twotone_visibility_off_24px
      };
      titles = new int[]{
              R.string.Page1Title,
              R.string.Page2Title,
              R.string.Page3Title,
              R.string.Page4Title,
              R.string.Page5Title,
              R.string.Page6Title,
              R.string.Page7Title
      };
      messages = new int[]{
              R.string.Page1Message,
              R.string.Page2Message,
              R.string.Page3Message,
              R.string.Page4Message,
              R.string.Page5Message,
              R.string.Page6Message,
              R.string.Page7Message
      };
    }
  }

  void initPager(@NonNull Context context)
  {
    viewPager = findViewById(R.id.intro_view_pager);
    topImage1 = findViewById(R.id.icon_image1);
    topImage2 = findViewById(R.id.icon_image2);
    bottomPages = findViewById(R.id.bottom_pages);
    topImage2.setVisibility(View.GONE);
    viewPager.setAdapter(new IntroAdapter(context));
    viewPager.setPageMargin(0);
    viewPager.setOffscreenPageLimit(1);
    viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
      @Override
      public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

      }

      @Override
      public void onPageSelected(int i) {

      }

      @Override
      public void onPageScrollStateChanged(int i) {
        if (i == ViewPager.SCROLL_STATE_IDLE || i == ViewPager.SCROLL_STATE_SETTLING) {
          if (lastPage != viewPager.getCurrentItem()) {
            lastPage = viewPager.getCurrentItem();

            final ImageView fadeoutImage;
            final ImageView fadeinImage;
            if (topImage1.getVisibility() == View.VISIBLE) {
              fadeoutImage = topImage1;
              fadeinImage = topImage2;

            } else {
              fadeoutImage = topImage2;
              fadeinImage = topImage1;
            }

            fadeinImage.bringToFront();
            fadeinImage.setImageResource(icons[lastPage]);
            fadeinImage.clearAnimation();
            fadeoutImage.clearAnimation();

            Animation outAnimation = AnimationUtils.loadAnimation(context, R.anim.uf_icon_anim_fade_out);
            outAnimation.setAnimationListener(new Animation.AnimationListener() {
              @Override
              public void onAnimationStart(Animation animation) {
              }

              @Override
              public void onAnimationEnd(Animation animation) {
                fadeoutImage.setVisibility(View.GONE);
              }

              @Override
              public void onAnimationRepeat(Animation animation) {

              }
            });

            Animation inAnimation = AnimationUtils.loadAnimation(context, R.anim.uf_icon_anim_fade_in);
            inAnimation.setAnimationListener(new Animation.AnimationListener() {
              @Override
              public void onAnimationStart(Animation animation) {
                fadeinImage.setVisibility(View.VISIBLE);
              }

              @Override
              public void onAnimationEnd(Animation animation) {
              }

              @Override
              public void onAnimationRepeat(Animation animation) {

              }
            });

            fadeoutImage.startAnimation(outAnimation);
            fadeinImage.startAnimation(inAnimation);
          }
        }
      }
    });

    if (justCreated) {
      if (false/*LocaleController.isRTL*/) {
        viewPager.setCurrentItem(6);
        lastPage = 6;
      } else {
        viewPager.setCurrentItem(0);
        lastPage = 0;
      }
      justCreated = false;
    }
  }

  private class IntroAdapter extends PagerAdapter
  {
    final Context context;

    IntroAdapter(@NonNull Context context)
    {
      this.context  = context;
    }

    @Override
    public int getCount() {
      return 7;
    }

    @Override
    public Object instantiateItem(ViewGroup container, int position) {
      View view = View.inflate(container.getContext(), R.layout.uf_intro_view_layout, null);
      TextView headerTextView = view.findViewById(R.id.header_text);
      TextView messageTextView = view.findViewById(R.id.message_text);
      container.addView(view, 0);

      headerTextView.setText(context.getString(titles[position]));
      //messageTextView.setText(AndroidUtilities.replaceTags(getString(messages[position])));
      messageTextView.setText(Utils.replaceTags(context.getString(messages[position])));
      return view;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
      container.removeView((View) object);
    }

    @Override
    public void setPrimaryItem(ViewGroup container, int position, Object object) {
      super.setPrimaryItem(container, position, object);
      int count = bottomPages.getChildCount();
      for (int a = 0; a < count; a++) {
        View child = bottomPages.getChildAt(a);
        if (a == position) {
          child.setBackgroundColor(getContext().getResources().getColor(R.color.signal_accent_primary));
        } else {
          child.setBackgroundColor(0xffbbbbbb);
        }
      }
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
      return view.equals(object);
    }

    @Override
    public void restoreState(Parcelable arg0, ClassLoader arg1) {
    }

    @Override
    public Parcelable saveState() {
      return null;
    }

    @Override
    public void unregisterDataSetObserver(DataSetObserver observer) {
      if (observer != null) {
        super.unregisterDataSetObserver(observer);
      }
    }
  }

}