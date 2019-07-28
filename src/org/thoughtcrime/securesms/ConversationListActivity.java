/**
 * Copyright (C) 2014 Open Whisper Systems
 * Copyright (C) 2016 unfacd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import com.kongzue.dialog.v3.MessageDialog;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import android.os.Handler;
import android.provider.ContactsContract;
import androidx.fragment.app.Fragment;
import androidx.core.view.MenuItemCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.TooltipCompat;
import android.text.TextUtils;
import org.thoughtcrime.securesms.logging.Log;

import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.protobuf.ByteString;
import com.mikepenz.actionitembadge.library.utils.BadgeStyle;
import com.ogaclejapan.smarttablayout.SmartTabLayout;
import com.ogaclejapan.smarttablayout.utils.v4.FragmentPagerItems;
import com.ogaclejapan.smarttablayout.utils.v4.FragmentStatePagerItemAdapter;
import com.tomergoldst.tooltips.ToolTip;
import com.tomergoldst.tooltips.ToolTipsManager;
import com.unfacd.android.data.json.JsonEntityStateSync;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvcmd.events.AppEventConversationListNotification;
import com.unfacd.android.ufsrvcmd.events.AppEventMessageNotification;
import com.unfacd.android.ufsrvcmd.events.AppEventNotificationWSConnection;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefNickname;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefUserAvatar;
import com.unfacd.android.ufsrvcmd.events.LocationV1;
import com.unfacd.android.ufsrvcmd.events.LocationV1SystemEvent;
import com.unfacd.android.ufsrvcmd.events.StateSyncV1SystemEvent;
import com.unfacd.android.ui.FenceMapActivity;
import com.unfacd.android.ui.components.intro_contact.IntroContactDescriptor;
import com.unfacd.android.ui.components.intro_contact.IntroContactView;
import com.unfacd.android.ui.components.InvitedToGroupDialog;
import com.unfacd.android.ui.components.MessageDialogCloseListener;
import com.unfacd.android.ui.components.TintableImage;
import com.unfacd.android.ui.hostfrgament.BackStackFragment;
import com.unfacd.android.ui.hostfrgament.HostFragment;
import com.unfacd.android.utils.Utils;


import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.thoughtcrime.securesms.conversation.ConversationActivity;
import org.thoughtcrime.securesms.components.RatingManager;
import org.thoughtcrime.securesms.components.SearchToolbar;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase.MarkedMessageInfo;
import org.thoughtcrime.securesms.notifications.MarkReadReceiver;
import org.thoughtcrime.securesms.lock.RegistrationLockDialog;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.database.loaders.ConversationListLoader;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientModifiedListener;
import org.thoughtcrime.securesms.search.SearchFragment;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConversationListActivity extends PassphraseRequiredActionBarActivity
    implements  ConversationListFragment.ConversationSelectedListener,
                InvitedToGroupDialog.DialogButtonListener, SmartTabLayout.TabProvider, MessageDialogCloseListener //
{
  @SuppressWarnings("unused")
  private static final String TAG = ConversationListActivity.class.getSimpleName();

  private final DynamicTheme    dynamicTheme    = new DynamicNoActionBarTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private ConversationListFragment conversationListFragment;
  private SearchFragment           searchFragment;
  private ContentObserver          observer;
  private SearchToolbar            searchToolbar;
  private ImageView                searchAction;
  private ViewGroup                fragmentContainer;

  //
  public static final String INTROCONTACT_MSGID    = "introcontact_msgid";
  public static final String INTROCONTACT_UFSRVUID = "introcontact_ufsrvuid";
  public static final String INTROCONTACT_MSG      = "introcontact_msg";
  public static final String INTROCONTACT_AVATAR   = "introcontact_avatar";

  private   TextView      badgeLocationView;
  static final public    BadgeStyle    badgeStyleUnread = new BadgeStyle(BadgeStyle.Style.DEFAULT, R.layout.menu_action_item_badge, Color.parseColor("#ff8833"), Color.parseColor("#ff7733"), Color.parseColor("#EEEEEE"));
  private   MenuItem      itemBadgeUnread;
  private   MenuItem      itemSearch;
  private   TintableImage tintableImgaeUnfacd;
  private   TextView      nickname;
  private   Recipient     thisRecipient;

  private ActivityResultChildViewListener activityResultChildViewListener = null;
//  private AvatarDownloadedListener        avatarDownloadedListener        = null;

  final     ToolTipsManager toolTipsManager = new ToolTipsManager();
  private   ToolTip       toolTip;
  private   View          toolTipViewAnchor;
  //

  ////////////
  private ArrayList<HostFragment> conversationFragments = new ArrayList<>();
  private HostFragment conversationListHostFragmentCurrent;
  private HostFragment conversationListFragmentOpen;
  private HostFragment conversationListFragmentInvited;
  private HostFragment conversationListFragmentLeft;

  private SmartTabLayout smartTabLayout;
  FragmentStatePagerItemAdapter pagerAdapter;

  private NicknameObserver thisNicknameObserver;
  private RelativeLayout  ufnameContainerLayout;
  private Toolbar toolbar;
  private ViewPager viewPager;
  static private Pair<Integer, Integer> [] tipsCopy= new Pair [] {
          new Pair(Integer.valueOf(R.string.conversation_list_pager_open_conversations), Integer.valueOf(ToolTip.GRAVITY_RIGHT)),
          new Pair(Integer.valueOf(R.string.conversation_list_pager_invited_conversations),  Integer.valueOf(ToolTip.GRAVITY_CENTER)),
          new Pair(Integer.valueOf(R.string.conversation_list_pager_left_conversations),  Integer.valueOf(ToolTip.GRAVITY_LEFT))
  };
  //////////

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }


  //////////////////////////////////////////////////////////////

  @Override
  protected void onCreate(Bundle icicle, boolean ready)
  {
    final Context activityContext = this;//

    setContentView(R.layout.uf_conversation_list_activity);

    toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayShowTitleEnabled(false);

    ufnameContainerLayout = findViewById(R.id.toolbar_unfacd_name_container);
    ufnameContainerLayout.setVisibility(View.VISIBLE);
    nickname = toolbar.findViewById(R.id.toolbar_nickname);
    thisRecipient = Recipient.from(this, Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(this)), true);
    nickname.setText(TextUtils.isEmpty(thisRecipient.getNickname()) ?
                     TextSecurePreferences.getUfsrvNickname(this) :
                     thisRecipient.getNickname());

    searchToolbar            = findViewById(R.id.search_toolbar);
    searchAction             = findViewById(R.id.search_action);
    fragmentContainer        = findViewById(R.id.fragment_container);

    tintableImgaeUnfacd = toolbar.findViewById(R.id.toolbar_unfacd_name);
    tintableImgaeUnfacd.setImageDrawable(getResources().getDrawable(R.drawable.ic_unfacd_name_white));
    tintableImgaeUnfacd.setOnClickListener (v -> {
        Intent intent = new Intent(activityContext, CreateProfileActivity.class);
        intent.putExtra(CreateProfileActivity.EXCLUDE_SYSTEM, true);
        startActivity(intent);
    });

    if (thisRecipient != null) {
      thisRecipient.addListener(new RecipientModifiedListener() {
        @Override
        public void onModified (Recipient recipient) {
          ConversationListActivity.this.runOnUiThread(() -> refreshNickname());
        }
      });
    }
    else  Log.e(TAG, String.format("onCreate: ERROR: COULD NOT Add thsirecipient nick"));

    initialisePagerResources();

    initializeSearchListener();

    initializeThisNicknameUpdatesReceiver();//

    RatingManager.showRatingDialogIfNecessary(this);
    RegistrationLockDialog.showReminderIfNecessary(this);
    TooltipCompat.setTooltipText(searchAction, getText(R.string.SearchToolbar_search_for_conversations_contacts_and_messages));

  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState)
  {
    super.onPostCreate(savedInstanceState);
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig)
  {
    super.onConfigurationChanged(newConfig);
  }

  public void toggleActionFloatMenu(boolean toggleMenu, boolean toggleButton, boolean fastClose) {
//    boolean firstPage = true;//gDataPreferences.getViewPagersLastPage() == 0;
//
//    ((RelativeLayout) findViewById(R.id.fastclose)).setVisibility(fastClose?View.INVISIBLE:View.VISIBLE);
//
//    if(toggleMenu) {
//      if (actionFloatMenu.getVisibility() == View.INVISIBLE) {
//        if(firstPage) {
//          actionFloatMenu.setVisibility(actionFloatMenu.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
//          actionFloatMenu.startAnimation(slideUp);
//        }
//      } else {
//        if(firstPage) {
//          actionFloatMenu.startAnimation(slideDown);
//          actionFloatMenu.setVisibility(actionFloatMenu.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
//        } else {
//          actionFloatMenu.startAnimation(slideDown);
//          actionFloatMenu.setVisibility(View.INVISIBLE);
//        }
//      }
//    }
//    if(toggleButton) {
//      if (fab.getVisibility() == View.INVISIBLE) {
//        if(firstPage) {
//          fab.setVisibility(fab.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
//          fab.startAnimation(slideUp);
//        }
//      } else {
//        fab.startAnimation(slideDown);
//        fab.setVisibility(fab.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
//      }
//    }
//    if(actionFloatMenu.getVisibility() == View.VISIBLE &&  findViewById(R.id.fastclose).getVisibility() == View.VISIBLE) {
//      findViewById(R.id.overlay_gray).setVisibility(View.VISIBLE);
//      findViewById(R.id.overlay_gray).setOnClickListener(new View.OnClickListener() {
//        @Override
//        public void onClick(View view) {
//          toggleActionFloatMenu(true, false, false);
//        }
//      });
//      fab.setImageBitmap(((BitmapDrawable)getResources().getDrawable(R.drawable.ic_cancel_white_24dp)).getBitmap());
//    } else {
//      fab.setImageBitmap(((BitmapDrawable)getResources().getDrawable(R.drawable.ic_add_white_24dp)).getBitmap());
//      findViewById(R.id.overlay_gray).setVisibility(View.INVISIBLE);
//    }
  }

  private void initialisePagerResources () {
//    tabs.add(HostFragment.newInstance(ContentFragment.newInstance(1, 30)));
    conversationListFragmentOpen    = HostFragment.newInstance(ConversationListFragment.newInstance(dynamicLanguage.getCurrentLocale(), ConversationListLoader.GroupMode.GROUP_MODE_LOADER_OPEN));
    conversationListFragmentInvited = HostFragment.newInstance(ConversationListFragment.newInstance(dynamicLanguage.getCurrentLocale(), ConversationListLoader.GroupMode.GROUP_MODE_LOADER_INVITED));
    conversationListFragmentLeft    = HostFragment.newInstance(ConversationListFragment.newInstance(dynamicLanguage.getCurrentLocale(), ConversationListLoader.GroupMode.GROUP_MODE_LOADER_LEFT));
    conversationFragments.add(conversationListFragmentOpen);
    conversationFragments.add(conversationListFragmentInvited);
    conversationFragments.add(conversationListFragmentLeft);

    pagerAdapter = new FragmentStatePagerItemAdapter(
            getSupportFragmentManager(), FragmentPagerItems.with(this)
            .add("OPEN", conversationListFragmentOpen.getClass())
            .add("INVITED", conversationListFragmentInvited.getClass())
            .add("LEFT", conversationListFragmentLeft.getClass())
            .create()){
      @Override
      public int getItemPosition(Object object) {
        return POSITION_NONE;
      }

      @Override
      public Fragment getItem(int i) {
        return conversationFragments.get(i);
      }
    };

    viewPager = findViewById(R.id.pager);
    viewPager.setAdapter(pagerAdapter);
    viewPager.setCurrentItem(TextSecurePreferences.getLastSelectedPager(getBaseContext()));
    conversationListHostFragmentCurrent = conversationFragments.get(TextSecurePreferences.getLastSelectedPager(getBaseContext()));
    conversationListFragment = (ConversationListFragment) conversationListHostFragmentCurrent.getHostedFragment();

    smartTabLayout = findViewById(R.id.sliding_tabs);
    smartTabLayout.setCustomTabView(this);//IMPORTANT: this must be called before  smartTabLayout.setViewPager(viewPager);
    smartTabLayout.setViewPager(viewPager);

//    initializeContactUpdatesReceiver();

    smartTabLayout.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
      @Override
      public void onPageScrolled(int i, float v, int i2) {

      }

      @Override
      public void onPageSelected(int i) {
        TextSecurePreferences.setLastSelectedPager(getBaseContext(), i);
        supportInvalidateOptionsMenu();
        //viewPager.getAdapter().notifyDataSetChanged(); //hopefully, we should need to have to reload the adapter purely on page scroll event
        toggleActionFloatMenu(i != 0, true, false);

        View tab = smartTabLayout.getTabAt(i);
        View mark = tab.findViewById(R.id.custom_tab_notification_mark);

        if (getUnreadCountForTab(ConversationListLoader.GroupMode.values()[i])>0)   mark.setVisibility(View.VISIBLE);

        if (TextSecurePreferences.getTooltipWithIdx (getBaseContext(), i))
        {
          displayPagerTooltip(getBaseContext(), (ViewGroup) tab.getParent().getParent().getParent(),
                  tab, getBaseContext().getString(tipsCopy[i].first.intValue()),
                              tipsCopy[i].second.intValue());

          TextSecurePreferences.setTooltipWithIdx (getBaseContext(), i, false);
        }

        //multiplex the conversationListFragment
        conversationListHostFragmentCurrent = (HostFragment)pagerAdapter.getPage(i);
        conversationListFragment =(ConversationListFragment)conversationListHostFragmentCurrent.getHostedFragment();
      }

      @Override
      public void onPageScrollStateChanged(int i) {

      }
    });
  }

  @Override
  public View createTabView(ViewGroup container, int position, PagerAdapter adapter) {
    LayoutInflater inflater = LayoutInflater.from(container.getContext());
    Resources res = container.getContext().getResources();
    View tab = inflater.inflate(R.layout.uf_conversation_list_pager_tabs, container, false);
    View mark = tab.findViewById(R.id.custom_tab_notification_mark);
    mark.setVisibility(View.GONE);
    ImageView icon = (ImageView) tab.findViewById(R.id.custom_tab_icon);
    switch (position) {
      case 0:
        icon.setImageDrawable(res.getDrawable(R.drawable.ic_call_received_made_black_24dp));
        if (getUnreadCountForTab(ConversationListLoader.GroupMode.values()[position])>0)   mark.setVisibility(View.VISIBLE);
//        icon.setImageDrawable(res.getDrawable(R.drawable.ld_call_received_made_black_24dp));
//        setBadgeCount (getBaseContext(), (LayerDrawable) icon.getDrawable(), "99");
        break;
      case 1:
        icon.setImageDrawable(res.getDrawable(R.drawable.ic_call_received_black_24dp));
        if (getUnreadCountForTab(ConversationListLoader.GroupMode.values()[position])>0)   mark.setVisibility(View.VISIBLE);
//        icon.setImageDrawable(res.getDrawable(R.drawable.ld_call_received_black_24dp));
//        setBadgeCount (getBaseContext(), (LayerDrawable) icon.getDrawable(), "2");

        break;
      case 2:
        icon.setImageDrawable(res.getDrawable(R.drawable.ic_call_made_black_24dp));
        if (getUnreadCountForTab(ConversationListLoader.GroupMode.values()[position])>0)   mark.setVisibility(View.VISIBLE);
//        icon.setImageDrawable(res.getDrawable(R.drawable.ld_call_made_black_24dp));
//        setBadgeCount (getBaseContext(), (LayerDrawable) icon.getDrawable(), "0");
        break;
      default:
        throw new IllegalStateException("Invalid position: " + position);
    }
    return tab;
  }

  //todo: ugly hack refactor with object based implementation
  long getUnreadCountForTab (ConversationListLoader.GroupMode groupMode)
  {
    long unreadCount = 0;

    switch  (groupMode)
    {
      case GROUP_MODE_LOADER_OPEN:
      unreadCount=DatabaseFactory.getThreadDatabase(ConversationListActivity.this).getOpenConversationListUnreadCount();
        break;

      case GROUP_MODE_LOADER_INVITED:
        unreadCount=DatabaseFactory.getThreadDatabase(ConversationListActivity.this).getInvitedConversationListUnreadCount();
        break;

      case GROUP_MODE_LOADER_LEFT:
        unreadCount=DatabaseFactory.getThreadDatabase(ConversationListActivity.this).getLeftConversationListUnreadCount();
        break;
    }

    return unreadCount;
  }

  //
  public void displayPagerTooltip (Context context, ViewGroup viewGroup, final View viewAnchor, String tip, int alignment) {
    if (Build.VERSION.SDK_INT > 15) {
      if (toolTipViewAnchor != null) {
        toolTipsManager.findAndDismiss(toolTipViewAnchor);

        if (viewAnchor==toolTipViewAnchor)  {
          toolTipViewAnchor = null;
          return;
        }
      }

      toolTipViewAnchor = viewAnchor;
      toolTip = new ToolTip.Builder(context, toolTipViewAnchor, viewGroup,
                                    tip,
                                    ToolTip.POSITION_BELOW)
              .setBackgroundColor(getResources().getColor(R.color.uf_primary_dark))
              .setAlign(alignment)
              .build();

      toolTipsManager.show(toolTip);

      viewAnchor.postDelayed(new Runnable() {
        @Override
        public void run() {
          if (toolTipViewAnchor!=null) {
            toolTipsManager.findAndDismiss(toolTipViewAnchor);
            toolTipViewAnchor = null;
          }
        }
      }, 10000);
    }

  }
  //////////////////////////////////////////////////

  @Override
  public void onResume() {
    super.onResume();

    ApplicationContext.getInstance().getUfsrvcmdEvents().register(this); //

    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);

    if (!TextSecurePreferences.isWebsocketRegistered(this)) {
      handleInvalidSession();
    }

    SimpleTask.run(getLifecycle(), () -> {
      return Recipient.from(this, Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(this)), false);// ufsrvid
    }, this::initializeProfileIcon);

    launchUnfacdIntroContactIfNecessary ();
  }

  // for when invoked externally mid cycle
  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
  }

  private Optional<Pair<Long, IntroContactDescriptor>> descriptorFromIntent () {
    return DatabaseFactory.getUnfacdIntroContactsDatabase(this).getIntroContact(getIntent().getLongExtra(INTROCONTACT_MSGID, -1));
  }

  private void launchUnfacdIntroContactIfNecessary ()
  {
    if (isLaunchedWithIntroContact()) {
      Optional<Pair<Long, IntroContactDescriptor>> descriptor = descriptorFromIntent();
      if (descriptor.isPresent()) {
        launchUnfacdIntroContact(descriptor);
      } else {
        Log.wtf(TAG, String.format("launchUnfacdIntroContactIfNecessary: Launched with IntroContactDescriptor '%d', but could not retrieve db record", getIntent().getLongExtra(INTROCONTACT_MSGID, -1)));
      }
    }
  }

  //
  @Override
  protected void onPause() {
    super.onPause();
    //
    getIntent().removeExtra(INTROCONTACT_MSGID);
    ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
    if (thisNicknameObserver != null) getContentResolver().unregisterContentObserver(thisNicknameObserver);
  }

  @Override
  public void onDestroy() {
    if (observer != null) getContentResolver().unregisterContentObserver(observer);
    super.onDestroy();
  }

// clean up and separated onCreare from onPrepare
@Override
  public boolean onPrepareOptionsMenu(Menu menu) {

  menu.findItem(R.id.menu_clear_passphrase).setVisible(!TextSecurePreferences.isPasswordDisabled(this));

  Utils.actionItemdescribeLocation(badgeLocationView, ufLocation.getInstance(), false);

  long unread = DatabaseFactory.getMmsSmsDatabase(getBaseContext()).getUnreadCount(getBaseContext());
  if (Utils.actionItemDescribeUnread(this, itemBadgeUnread, badgeStyleUnread, (int)unread)) {
    invalidateOptionsMenu();
  }

  super.onPrepareOptionsMenu(menu);

  return true;
}

  // seperate the instantiation of from onPrepareOptionMenue
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.text_secure_normal, menu);

    //temp disablement
    //getMenuInflater().inflate(R.menu.conversation_list, menu);// orig contains search action item
    getMenuInflater().inflate(R.menu.uf_conversation_list, menu);

    final View actionViewL = MenuItemCompat.getActionView(menu.findItem(R.id.menu_location));

    badgeLocationView = (TextView)actionViewL.findViewById(R.id.location_badge);
    itemBadgeUnread   =  menu.findItem(R.id.item_badge_unread);
    itemSearch        = menu.findItem(R.id.menu_search);

    initializeSearchListener();

    super.onCreateOptionsMenu(menu);

    return true;
  }

  private void initializeSearchListener() {
    searchAction.setOnClickListener(v -> {
      Permissions.with(this)
              .request(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
              .ifNecessary()
              .onAllGranted(() -> searchToolbar.display(searchAction.getX() + (searchAction.getWidth() / 2),
                                                        searchAction.getY() + (searchAction.getHeight() / 2)))
              .withPermanentDenialDialog(getString(R.string.ConversationListActivity_signal_needs_contacts_permission_in_order_to_search_your_contacts_but_it_has_been_permanently_denied))
              .execute();
    });

    searchToolbar.setListener(new SearchToolbar.SearchListener() {
      @Override
      public void onSearchTextChange(String text) {
        String trimmed = text.trim();

        if (trimmed.length() > 0) {
          if (searchFragment == null) {
            searchFragment = SearchFragment.newInstance(dynamicLanguage.getCurrentLocale());

            conversationListHostFragmentCurrent.replaceFragment(searchFragment, true);//
          }
          searchFragment.updateSearchQuery(trimmed);
        } else if (searchFragment != null) {
          conversationListHostFragmentCurrent.replaceFragment(searchFragment, true);//
          searchFragment = null;
        }
      }

      @Override
      public void onSearchClosed() {
        if (searchFragment != null) {
          conversationListHostFragmentCurrent.replaceFragment(searchFragment, true);//
          searchFragment = null;
        }
      }
    });
  }

  private void initializeProfileIcon(@NonNull Recipient recipient) {
    //-
//    ImageView     icon          = findViewById(R.id.toolbar_icon);
//    String        name          = Optional.fromNullable(recipient.getName()).or(Optional.fromNullable(TextSecurePreferences.getProfileName(this))).or("");
//    MaterialColor fallbackColor = recipient.getColor();
//
//    if (fallbackColor == ContactColors.UNKNOWN_COLOR && !TextUtils.isEmpty(name)) {
//      fallbackColor = ContactColors.generateFor(name);
//    }
//
//    Drawable fallback = new GeneratedContactPhoto(name, R.drawable.ic_profile_default).asDrawable(this, fallbackColor.toAvatarColor(this));
//
//    GlideApp.with(this)
//            .load(new ProfileContactPhoto(recipient.getAddress(), String.valueOf(TextSecurePreferences.getProfileAvatarId(this))))
//            .error(fallback)
//            .circleCrop()
//            .diskCacheStrategy(DiskCacheStrategy.ALL)
//            .into(icon);
//
//    icon.setOnClickListener(v -> handleDisplaySettings());
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {

    case R.id.menu_new_public_group:  createPublicGroup();     return true;
    case R.id.menu_new_group:         createGroup();           return true;
    case R.id.menu_intro_contact:     launchUnfacdIntroContact(Optional.absent()); return true;
    case R.id.menu_settings:          handleDisplaySettings(); return true;
    case R.id.menu_clear_passphrase:  handleClearPassphrase(); return true;
    case R.id.menu_mark_all_read:     handleMarkAllRead();     return true;
    case R.id.menu_invite:            handleInvite();          return true;
    case R.id.menu_help:              handleHelp();            return true;
    }

    return false;
  }

  @Override
  public void onCreateConversation(long threadId, Recipient recipient, int distributionType, long lastSeen) {
    openConversation(threadId, recipient, distributionType, lastSeen, -1);
  }

  public void openConversation(long threadId, Recipient recipient, int distributionType, long lastSeen, int startingPosition) {
    //
    long fid=DatabaseFactory.getThreadDatabase(this).getFidForThreadId(threadId);
    if (DatabaseFactory.getGroupDatabase(this).isGroupInvitationPending(fid)) {
      //check the interface callback for when a dialog button is pressed
      InvitedToGroupDialog invitedToGroupDialog= new InvitedToGroupDialog(this, recipient, threadId);
      invitedToGroupDialog.display();

      if (invitedToGroupDialog.isJoinAccepted()) {
        //switch to threadid as per rge code conversationListFragment below
      } else {
        //nothing
        Log.d(TAG, String.format("onCreateConversation: thread '%d' invitation to joing was not accepted yet", threadId));
        return;
      }
    }
    //

    searchToolbar.clearFocus();

    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(ConversationActivity.ADDRESS_EXTRA, recipient.getAddress());
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, distributionType);
    intent.putExtra(ConversationActivity.TIMING_EXTRA, System.currentTimeMillis());
    intent.putExtra(ConversationActivity.LAST_SEEN_EXTRA, lastSeen);
    intent.putExtra(ConversationActivity.STARTING_POSITION_EXTRA, startingPosition);

    intent.putExtra(ConversationActivity.GROUP_FID_EXTRA, fid); //

    startActivity(intent);
    overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
  }

  @Override
  public void onSwitchToArchive() {
    Intent intent = new Intent(this, ConversationListArchiveActivity.class);
    startActivity(intent);
  }

  @Override
  public void onBackPressed() {
    if (searchToolbar.isVisible()) searchToolbar.collapse();
    else {//
      if(!BackStackFragment.handleBackPressed(getSupportFragmentManager())){
        super.onBackPressed();
      }
    }//
//    else                           super.onBackPressed();//-
  }

  void createGroup() {
    Intent intent = new Intent(this, GroupCreateActivity.class);
    startActivity(intent);
  }

  private void createPublicGroup() {
    Intent intent = new Intent(this, FenceMapActivity.class);
    startActivity(intent);

  }

 void launchUnfacdIntroContact (Optional<Pair<Long, IntroContactDescriptor>> descriptor)
  {
    View customView = LayoutInflater.from(this).inflate(R.layout.custom_view_for_message_dialog, null);

    MessageDialog messageDialog;
    try {
      if (descriptor.isPresent()) {
        messageDialog = MessageDialog.build(this)
                .setTitle(R.string.InitiateContactDialog_unfacd_title_received).setMessage(R.string.InitiateContactDialog_unfacd_title_msg_received);
        ((IntroContactView)customView).setContainerContext(messageDialog)
                .setDescriptor(descriptor)
                .setCloseListener(this).finalise();
      } else {
        messageDialog = MessageDialog.build(this)
                .setTitle(R.string.InitiateContactDialog_unfacd_title).setMessage(R.string.InitiateContactDialog_unfacd_title_msg);
        ((IntroContactView) customView).setContainerContext(messageDialog).finalise();
      }

      setActivityResultChildViewListener((IntroContactView)customView);
    } catch (ClassCastException e) {
      throw new ClassCastException(this.toString() + "must inflate InitiateContactView");
    }

    messageDialog.setCustomView(customView).setCancelable(true).show();
  }

  private boolean isLaunchedWithIntroContact ()
  {
    return getIntent().getLongExtra(INTROCONTACT_MSGID, -1) > 0;
  }

  public static void launchMeWithIntroContactDescriptor (Context appContext, long introId)
  {
    Intent startActivity = new Intent();
    startActivity.setClass(appContext, ConversationListActivity.class);
    startActivity.setAction(ConversationListActivity.class.getName());
    startActivity.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
//    startActivity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

    startActivity.putExtra(INTROCONTACT_MSGID, introId);
    appContext.startActivity(startActivity);
  }

  @Override
  public void onMessageDialogClose (int button)
  {
    switch (button) {
      case 1:
      case 0:
        if (isLaunchedWithIntroContact()) {
//          if (!isTaskRoot ()) super.onBackPressed();

        }
        break;
    }
  }

  private void handleDisplaySettings() {
    Intent preferencesIntent = new Intent(this, ApplicationPreferencesActivity.class);
    startActivity(preferencesIntent);
  }

  private void handleClearPassphrase() {
    Intent intent = new Intent(this, KeyCachingService.class);
    intent.setAction(KeyCachingService.CLEAR_KEY_ACTION);
    startService(intent);
  }

  @SuppressLint("StaticFieldLeak")
  private void handleMarkAllRead() {
    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        Context                 context    = ConversationListActivity.this;
        List<MarkedMessageInfo> messageIds = DatabaseFactory.getThreadDatabase(context).setAllThreadsRead();

        MessageNotifier.updateNotification(context);
        MarkReadReceiver.process(context, messageIds);

        return null;
      }
   }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private void handleInvite() {
    startActivity(new Intent(this, InviteActivity.class));
  }

  private void handleHelp() {
    try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://help.unfacd.io")));
    } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.ConversationListActivity_there_is_no_browser_installed_on_your_device, Toast.LENGTH_LONG).show();
      }
  }

  //
  private void handleInvalidSession ()
  {
    AlertDialog.Builder dialog = new AlertDialog.Builder(this);
    dialog.setTitle(R.string.StateSync_invalid_session);
    dialog.setMessage(R.string.StateSync_unfacd_service_invalidated_this_copy_of_the_app);
    dialog.setPositiveButton(R.string.StateSync_invalid_session_cancel, null);
    dialog.setCancelable(false);
    AlertDialog shownDialog = dialog.show();
    Button button = shownDialog.getButton(AlertDialog.BUTTON_POSITIVE);
    button.setEnabled(false);
  }

  //was removed in https://github.com/signalapp/Signal-Android/commit/66e1be1aebc63b64eae873a48e50a5666ba58dd4#diff-9c22ffaf9ac63408d893f310c13a9d58
  private void initializeContactUpdatesReceiver() {
    observer = new ContentObserver(null) {
      @Override
      public void onChange(boolean selfChange) {
        super.onChange(selfChange);
        Log.w(TAG, "Detected android contact data changed, refreshing cache");
        ConversationListActivity.this.runOnUiThread(() -> conversationListFragment.getListAdapter().notifyDataSetChanged());
      }
    };

    getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI,
                                                 true, observer);
  }

  //
  public BadgeStyle getBadgeStyleUnread () {
    return badgeStyleUnread;
  }

  //A++
  // InvitedToGroupDialog.DialogButtonListener implementation
  //user responded to the prompt around invitation to joing a group
  @Override
  public int onDialogButtonClicked (int button, Recipient recipient, long threadId)
  {
    try {
      long timeNowInMillis = System.currentTimeMillis();
      String groupId = recipient.getAddress().serialize();
      DatabaseFactory.getGroupDatabase(this).setActive(groupId, false);

      //
      GroupDatabase.GroupRecord groupRec=DatabaseFactory.getGroupDatabase(this).getGroupByGroupId(groupId);
      //  Fencecommand context
      SignalServiceProtos.FenceCommand.Builder fenceCommandBuilder= MessageSender.buildFenceCommandJoinInvitationResponse(ApplicationContext.getInstance(), groupRec.getFid(), timeNowInMillis, true);
      SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandBuilder= SignalServiceProtos.UfsrvCommandWire.newBuilder()
              .setFenceCommand(fenceCommandBuilder.build())
              .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
      //
      SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
              .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupId)))
              .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
              .setFenceMessage(fenceCommandBuilder.build())//
              .build();

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(recipient, groupContext, null, timeNowInMillis, 0, 0,null, Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandBuilder.build());// ufsrvcommand
      MessageSender.send(this, outgoingMessage, threadId, false, null);

      DatabaseFactory.getGroupDatabase(this).markGroupMode(groupRec.getFid(), GroupDatabase.GROUP_MODE_INVITATION);
    } catch (IOException e) {
      Log.w(TAG, e);
      Toast.makeText(this, R.string.ConversationActivity_error_leaving_group, Toast.LENGTH_LONG).show();
    }
    return 0;
  }
  //

  //
  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data)
  {
    super.onActivityResult(requestCode, resultCode, data);
    if (activityResultChildViewListener != null) activityResultChildViewListener.onActivityResultForListener(requestCode, resultCode, data);
  }

  private void initializeThisNicknameUpdatesReceiver() {
    thisNicknameObserver  =  new NicknameObserver(new Handler());

    Uri thisNicknameUri=Uri.parse(IdentityDatabase.IDENTITY_URI+TextSecurePreferences.getUfsrvUserId(this));
    getContentResolver().registerContentObserver(thisNicknameUri, true, thisNicknameObserver);
  }

  void refreshNickname ()
  {
    nickname.setText(TextUtils.isEmpty(thisRecipient.getNickname())?"*":thisRecipient.getNickname());

  }

  //
  public interface ActivityResultChildViewListener {
    void onActivityResultForListener (int requestCode, int resultCode, Intent data);
  }

  public void setActivityResultChildViewListener (ActivityResultChildViewListener listener) {
    this.activityResultChildViewListener = listener;
  }

  class NicknameObserver extends ContentObserver {
    public NicknameObserver (Handler handler) {
      super(handler);
    }

    @Override
    public void onChange(boolean selfChange) {
      this.onChange(selfChange, null);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
      Log.d (TAG, "NicknameObserver.onChange: invoked...");
      refreshNickname();
    }
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventNotificationWSConnection event){
    Log.d(TAG, String.format("AppEventNotificationWSConnection: CONNECTON STATE IS '%b'", event.getOnOffState()));
    if (event.getOnOffState())   tintableImgaeUnfacd.setSelected(true);
    else
    {
      tintableImgaeUnfacd.setSelected(false);
      Utils.shakeView(tintableImgaeUnfacd, 20, 0);

      tintableImgaeUnfacd.postDelayed(new Runnable() {
        @Override
        public void run() {
          //toolTipsManager.findAndDismiss(viewAnchor);
        }
      }, 10000);
    }
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventMessageNotification event){
    if (Utils.actionItemDescribeUnread(this, itemBadgeUnread, badgeStyleUnread, event.getMsgCount()))
    {
      invalidateOptionsMenu();
    }
  }

  @Subscribe(sticky = false, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventConversationListNotification event)
  {
    for (ConversationListLoader.GroupMode groupModeValue : ConversationListLoader.GroupMode.values()) {
      View tab = smartTabLayout.getTabAt(groupModeValue.ordinal());
      View mark = tab.findViewById(R.id.custom_tab_notification_mark);

      if (getUnreadCountForTab(ConversationListLoader.GroupMode.values()[groupModeValue.ordinal()])>0)   mark.setVisibility(View.VISIBLE);
      else  mark.setVisibility(View.GONE);
    }
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(LocationV1SystemEvent event){
    //msgbus posts too early, before view were initialised
    if (badgeLocationView!=null) {
      Utils.actionItemdescribeLocation(badgeLocationView, ((ufLocation) event.getEventData()), true);
      final ufLocation location = ((ufLocation) (event.getEventData()));

      Utils.actionItemdescribeLocation(badgeLocationView, location, false);
      invalidateOptionsMenu();
    }
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(LocationV1 event){
    //Utils.actionItemdescribeLocation(badgeLocationView, ((ufLocation) event.getEventData()), true);
    Log.d(TAG, "LocationV1: eventName: "+event.getEventName());
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(StateSyncV1SystemEvent event){
    JsonEntityStateSync stateSync = (JsonEntityStateSync)event.getEventData();
    if (stateSync.getSessionState()>0) {
        handleInvalidSession();
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventPrefNickname event)
  {
    if (event.getUid().longValue()==TextSecurePreferences.getUserId(ApplicationContext.getInstance()).longValue()) {
      refreshNickname();
      invalidateOptionsMenu();
      postTimedNotification(getString(R.string.your_nickname_successfully_changed));
      Log.d(TAG, "AppEventPrefNickname: own nickname: "+event.getNickname());
    } else {
      ConversationListActivity.this.runOnUiThread(() -> conversationListFragment.getListAdapter().notifyDataSetChanged());
      Log.d(TAG, "AppEventPrefNickname: uid: "+event.getUid());
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventPrefUserAvatar event)
  {
    if (event.getUid().longValue()==TextSecurePreferences.getUserId(ApplicationContext.getInstance()).longValue()) {
      postTimedNotification(getString(R.string.your_avatar_successfully_changed));
    } else {
      //other user todo: not needed? causes exception as conversationListFragment.getListAdapter()returns null
//      ConversationListActivity.this.runOnUiThread(() -> conversationListFragment.getListAdapter().notifyDataSetChanged());
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }

}
