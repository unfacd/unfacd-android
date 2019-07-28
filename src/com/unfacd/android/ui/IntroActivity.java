/**
 * Copyright (C) 2015-2019 unfacd works
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.unfacd.android.ui;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.DataSetObserver;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.utils.Utils;

import org.thoughtcrime.securesms.BaseActivity;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

public class IntroActivity extends BaseActivity
{

  private ViewPager viewPager;
  private ImageView topImage1;
  private ImageView topImage2;
  private ViewGroup bottomPages;
  private int lastPage = 0;
  private boolean justCreated = false;
  private boolean startPressed = false;
  private int[] icons;
  private int[] titles;
  private int[] messages;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    requestWindowFeature(Window.FEATURE_NO_TITLE);

    if (Utils.isTablet()) {
      setContentView(R.layout.uf_intro_layout_tablet);
    } else {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
      setContentView(R.layout.uf_intro_layout);
    }

    if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL)  {
      icons = new int[]{
              R.drawable.twotone_visibility_off_24px,
              R.drawable.twotone_scatter_plot_24px,
              R.drawable.twotone_group_24px,
              R.drawable.twotone_done_all_24px,
              R.drawable.twotone_vpn_key_24px,
              R.drawable.twotone_place_24px,
              R.drawable.intro1
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
              R.drawable.intro1,
              R.drawable.twotone_place_24px,
              R.drawable.twotone_vpn_key_24px,
              R.drawable.twotone_how_to_reg_24px,
              R.drawable.twotone_group_24px,
              R.drawable.twotone_scatter_plot_24px,
              R.drawable.twotone_visibility_off_24px
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

    viewPager = findViewById(R.id.intro_view_pager);
    TextView startMessagingButton = findViewById(R.id.start_messaging_button);
    //startMessagingButton.setText(LocaleController.getString("StartMessaging", R.string.StartMessaging).toUpperCase());
    startMessagingButton.setText(R.string.StartMessaging);
    if (Build.VERSION.SDK_INT >= 21) {
      StateListAnimator animator = new StateListAnimator();
      animator.addState(new int[]{android.R.attr.state_pressed}, ObjectAnimator.ofFloat(startMessagingButton, "translationZ", Utils.dp(2), Utils.dp(4)).setDuration(200));
      animator.addState(new int[]{}, ObjectAnimator.ofFloat(startMessagingButton, "translationZ", Utils.dp(4), Utils.dp(2)).setDuration(200));
      startMessagingButton.setStateListAnimator(animator);
    }

    topImage1 = findViewById(R.id.icon_image1);
    topImage2 = findViewById(R.id.icon_image2);
    bottomPages = findViewById(R.id.bottom_pages);
    topImage2.setVisibility(View.GONE);
    viewPager.setAdapter(new IntroAdapter());
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

            Animation outAnimation = AnimationUtils.loadAnimation(IntroActivity.this, R.anim.uf_icon_anim_fade_out);
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

            Animation inAnimation = AnimationUtils.loadAnimation(IntroActivity.this, R.anim.uf_icon_anim_fade_in);
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

    startMessagingButton.setOnClickListener(v -> onStartClicked());

    justCreated = true;
  }

  @Override
  protected void onResume() {
    super.onResume();
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

  @Override
  protected void onPause() {
    super.onPause();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  private void onStartClicked () {
    if (startPressed) {
      return;
    }
    startPressed = true;
    TextSecurePreferences.setBooleanPreference(ApplicationContext.getInstance(), TextSecurePreferences.UFSRV_SEEN_INTRO, true);
    onContinue();
  }

  private void onContinue() {
    Permissions.with(this)
            .request(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS,
                     Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE,
                     Manifest.permission.READ_PHONE_STATE,
                     Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)//
            .ifNecessary()
            .withRationaleDialog(getString(R.string.RegistrationActivity_signal_needs_access_to_your_contacts_and_media_in_order_to_connect_with_friends),
                                 R.drawable.ic_contacts_white_48dp, R.drawable.ic_folder_white_48dp, R.drawable.ic_explore_white_48dp)
            .onAnyResult(() -> {
              TextSecurePreferences.setHasSeenWelcomeScreen(IntroActivity.this, true);

              Intent nextIntent = getIntent().getParcelableExtra("next_intent");

              if (nextIntent == null) {
                throw new IllegalStateException("Was not supplied a next_intent.");
              }

              startActivity(nextIntent);
              overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
              finish();
            })
            .execute();

  }

  private class IntroAdapter extends PagerAdapter {
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

      headerTextView.setText(getString(titles[position]));
      //messageTextView.setText(AndroidUtilities.replaceTags(getString(messages[position])));
      messageTextView.setText(Utils.replaceTags(getString(messages[position])));
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
          child.setBackgroundColor(0xff2ca5e0);
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
