/**
 * Copyright (C) 2014 Open Whisper Systems
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

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.textfield.TextInputLayout;
import androidx.appcompat.widget.Toolbar;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import org.thoughtcrime.securesms.logging.Log;

import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.kyleduo.switchbutton.SwitchButton;
import com.roughike.swipeselector.SwipeItem;
import com.roughike.swipeselector.SwipeSelector;
import com.unfacd.android.fence.EnumFencePermissions;
import com.unfacd.android.fence.FenceAlreadyExistsException;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ui.components.PrivateGroupForTwoName;

import net.cachapa.expandablelayout.ExpandableLayout;
import com.shawnlin.numberpicker.NumberPicker;

import org.thoughtcrime.securesms.avatar.AvatarSelection;
import org.thoughtcrime.securesms.conversation.ConversationActivity;
import org.thoughtcrime.securesms.components.PushRecipientsPanel;
import org.thoughtcrime.securesms.components.PushRecipientsPanel.RecipientsPanelChangedListener;
import org.thoughtcrime.securesms.contacts.ContactsCursorLoader.DisplayMode;
import org.thoughtcrime.securesms.contacts.RecipientsEditor;
import org.thoughtcrime.securesms.contacts.avatars.ContactColors;
import org.thoughtcrime.securesms.contacts.avatars.ResourceContactPhoto;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.GroupManager.GroupActionResult;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;
import org.thoughtcrime.securesms.util.SelectedRecipientsAdapter;
import org.thoughtcrime.securesms.util.SelectedRecipientsAdapter.OnRecipientDeletedListener;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.util.InvalidNumberException;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Activity to create and update groups
 *
 * @author Jake McGinty
 */

public class GroupCreateActivity extends PassphraseRequiredActionBarActivity
                                 implements OnRecipientDeletedListener,
                                            RecipientsPanelChangedListener
{

  private final static String TAG = GroupCreateActivity.class.getSimpleName();

  public static final String GROUP_ADDRESS_EXTRA = "group_recipient";
  public static final String GROUP_THREAD_EXTRA  = "group_thread";

  private final DynamicTheme    dynamicTheme    = new DynamicTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private static final int PICK_CONTACT = 1;
  public static final  int AVATAR_SIZE  = 210;

  private EditText     groupName;
  private ListView     lv;
  private ImageView    avatar;
  private TextView     creatingText;
  private Bitmap       avatarBmp;

  //
  public static final String EXISTING_CONTACTS_EXTRA    = "existing_contacts";
  private RecipientDatabase.RecipientSettings groupPreferences=null;
  TextInputLayout           groupEditLayout;
  View                      imageButton;
  SelectedRecipientsAdapter preExistingMembersAdapter = new SelectedRecipientsAdapter(this);
  boolean                   isGroupNameEditable       =  true;
  private       ExpandableLayout expandableLayoutAdvanced;
  private       ExpandableLayout expandableLayoutPermissions;
  private       SwitchButton switchButtonAdvanced;
  private       SwitchButton switchButtonPermissions;
  private       SwipeSelector deliveryModeSelector;
  private       SwipeSelector privacyModeSelector;
  private       SwipeSelector joinModeSelector;
  private       SwipeSelector semanticsModeSelector;
  private       NumberPicker numberPicker;
  private       PushRecipientsPanel recipientsPanel;

  @NonNull private Optional<GroupData> groupToUpdate = Optional.absent();

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle state, boolean ready) {
    setContentView(R.layout.group_create_activity);
    //noinspection ConstantConditions

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayUseLogoEnabled(false);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    initializeResources();
    initializeExistingGroup();
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
    updateViewState();
  }

  private boolean isSignalGroup() {
    return TextSecurePreferences.isPushRegistered(this) && !getAdapter().hasNonPushMembers(); //-
    /*//
    if (TextSecurePreferences.isPushRegistered(this))
    {
      if (isPublic)  return true; //if is public we dont care about hasNonPushMembers condition

      //push registered, wanting a private group
      if (!getAdapter().hasNonPushMembers()) return false;
      else
      return true;
    }

    return false;*/
  }

  private void disableSignalGroupViews(int reasonResId) {
    View pushDisabled = findViewById(R.id.push_disabled);
    pushDisabled.setVisibility(View.VISIBLE);
    ((TextView) findViewById(R.id.push_disabled_reason)).setText(reasonResId);
    avatar.setEnabled(false);
    groupName.setEnabled(false);
  }

  private void enableSignalGroupViews() {
    findViewById(R.id.push_disabled).setVisibility(View.GONE);
    avatar.setEnabled(true);
    if (isGroupNameEditable) groupName.setEnabled(true);
  }

  @SuppressWarnings("ConstantConditions")
  private void updateViewState() {
    if (!TextSecurePreferences.isPushRegistered(this)) {
      disableSignalGroupViews(R.string.GroupCreateActivity_youre_not_registered_for_signal);
      getSupportActionBar().setTitle(R.string.GroupCreateActivity_actionbar_mms_title);
    } else if (getAdapter().hasNonPushMembers()) {
      disableSignalGroupViews(R.string.GroupCreateActivity_contacts_dont_support_push);
      getSupportActionBar().setTitle(R.string.GroupCreateActivity_actionbar_mms_title);
    } else {
      enableSignalGroupViews();
      getSupportActionBar().setTitle(groupToUpdate.isPresent()
                                      ? R.string.GroupCreateActivity_actionbar_edit_title
                                     : R.string.GroupCreateActivity_actionbar_title);
    }
  }

  private static boolean isActiveInDirectory(Recipient recipient) {
    return recipient.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED;
  }

  private void addSelectedContacts(@NonNull Recipient... recipients) {
    new AddMembersTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, recipients);
  }

  private void addSelectedContacts(@NonNull Collection<Recipient> recipients) {
    addSelectedContacts(recipients.toArray(new Recipient[recipients.size()]));
  }

  private void initializeResources() {
    RecipientsEditor    recipientsEditor = ViewUtil.findById(this, R.id.recipients_text);
    recipientsPanel  = ViewUtil.findById(this, R.id.recipients);
    lv           = ViewUtil.findById(this, R.id.selected_contacts_list);
    avatar       = ViewUtil.findById(this, R.id.avatar);
    groupName    = ViewUtil.findById(this, R.id.group_name);
    creatingText = ViewUtil.findById(this, R.id.creating_group_text);
    SelectedRecipientsAdapter adapter= new SelectedRecipientsAdapter(this);
    adapter.setOnRecipientDeletedListener(this);
    lv.setAdapter(adapter);
    recipientsEditor.setHint(R.string.recipients_panel__add_members);
    recipientsPanel.setPanelChangeListener(this);

    // in  updating group mode, the click-listener is re-instantiated witha  new adapter containing exiting group members
    (imageButton=findViewById(R.id.contacts_button)).setOnClickListener(new AddRecipientButtonListener(adapter));//  adapter + imageButton
    //findViewById(R.id.contacts_button).setOnClickListener(new AddRecipientButtonListener());//-

    avatar.setImageDrawable(new ResourceContactPhoto(R.drawable.ic_group_white_24dp).asDrawable(this, ContactColors.UNKNOWN_COLOR.toConversationColor(this)));
    avatar.setOnClickListener(view -> AvatarSelection.startAvatarSelection(this, false, false));

    // support for floating edit label
    groupEditLayout = findViewById(R.id.groupEditLayout);
    groupEditLayout.setErrorEnabled(true);
    //

    //
    initialiseModeSelectors(false);
    groupName.addTextChangedListener(new TextWatcher() {

      public void afterTextChanged(Editable s) {
      }

      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      public void onTextChanged(CharSequence s, int start, int before, int count) {
        String cfname=ufLocation.getInstance().getBaseLocationPrefix()+getGroupName();
        if (DatabaseFactory.getGroupDatabase(GroupCreateActivity.this).isGroupAvailableByCname(cfname, true))  groupEditLayout.setError("GROUP ALREADY EXISTS!");
        else groupEditLayout.setError("");
      }
    });
    //

    switchButtonAdvanced = findViewById(R.id.switch_button);
    switchButtonAdvanced.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
          expandableLayoutAdvanced.expand();
        } else {
          if (expandableLayoutAdvanced.isExpanded()) {
            expandableLayoutAdvanced.collapse();
          }
        }
      }
    });

    expandableLayoutAdvanced = findViewById(R.id.expandable_layout_advanced);
    expandableLayoutAdvanced.setOnExpansionUpdateListener(new ExpandableLayout.OnExpansionUpdateListener() {
      @Override
      public void onExpansionUpdate(float expansionFraction, int state) {
        TextView switchButtonText=ViewUtil.findById(GroupCreateActivity.this, R.id.switch_button_text);
        switch (state)
        {
          case 0:
            switchButtonText.setText("See more advanced settings...");
            break;
          case 3:
            switchButtonText.setText("Advanced Group settings");
            break;
        }
      }
    });

    numberPicker = findViewById(R.id.number_picker);
    numberPicker.setOrientation(LinearLayout.VERTICAL);


    switchButtonPermissions = findViewById(R.id.switch_button_permission);
    switchButtonPermissions.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
          expandableLayoutPermissions.expand();
        } else {
          if (expandableLayoutPermissions.isExpanded()) {
            expandableLayoutPermissions.collapse();
          }
        }
      }
    });

    expandableLayoutPermissions = findViewById(R.id.expandable_layout_permissions);
    expandableLayoutPermissions.setOnExpansionUpdateListener(new ExpandableLayout.OnExpansionUpdateListener() {
      @Override
      public void onExpansionUpdate(float expansionFraction, int state) {
        TextView switchButtonText=ViewUtil.findById(GroupCreateActivity.this, R.id.switch_button_permissions_text);
        switch (state)
        {
          case 0:
            switchButtonText.setText("See permissions related settings...");
            break;
          case 3:
            switchButtonText.setText("Group permissions related settings...");
            break;
        }
      }
    });

  }

  private void initialiseModeSelectors (boolean isExistingGroup) {
    if (!isExistingGroup) {
      deliveryModeSelector = findViewById(R.id.delivery_mode_selector);
      joinModeSelector = findViewById(R.id.join_mode_selector);
      privacyModeSelector = findViewById(R.id.privacy_mode_selector);
      semanticsModeSelector = findViewById(R.id.semantics_mode_selector);
    }

    deliveryModeSelector.setItems(
            // The first argument is the value for that item, and should in most cases be unique for the
            // current SwipeSelector, just as you would assign values to radio buttons.
            // You can use the value later on to check what the selected item was.
            // The value can be any Object, here we're using ints.
            new SwipeItem("0", "Normal multi-way Mode", "Normal multi-way messaging."),
            new SwipeItem("1", "Broadcast Mode", "Messages are broadcast to the entire group. Replies only seen by onwer."),
            new SwipeItem("2", "One-way Mode", "Messages are broadcast to the entire group. No replies allowed.")
    );
    joinModeSelector.setItems(
            // The first argument is the value for that item, and should in most cases be unique for the
            // current SwipeSelector, just as you would assign values to radio buttons.
            // You can use the value later on to check what the selected item was.
            // The value can be any Object, here we're using ints.
            new SwipeItem("0", "Open Join Mode", "Anybody can join group"),
            new SwipeItem("1", "Invite-only Mode", "Only users with prior invitation can join."),
            new SwipeItem("2", "Open With Key Mode", "User must enter a private key set by group owner."),
            new SwipeItem("3", "Invite-only With Key Mode", "Users require invitation and private key to joing.")
    );
    privacyModeSelector.setItems(
            new SwipeItem("0", "Publicly Visible Mode", "Group is publically visible."),
            new SwipeItem("1", "Private Mode", "Group is hidden from public view.")
    );

    semanticsModeSelector.setItems(
            new SwipeItem("1", "Strict Mode", "Only specifically listed members are permitted (whitelising)."),
            new SwipeItem("2", "Relaxed Mode", "Group permissions are relaxed except for specifically listed members (blacklisting).")//default BLACKLISTING

    );
  }

  private void initializeExistingGroup() {
    final Address groupAddress = getIntent().getParcelableExtra(GROUP_ADDRESS_EXTRA);

    if (groupAddress != null) {
      new FillExistingGroupInfoAsyncTask(this, preExistingMembersAdapter).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, groupAddress.toGroupString());
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.group_create, menu);
    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
      case android.R.id.home:
        finish();
        return true;
      case R.id.menu_create_group:
        if (groupToUpdate.isPresent()) handleGroupUpdate();
        else                           handleGroupCreate();
        return true;
    }

    return false;
  }

  @Override
  public void onRecipientDeleted(Recipient recipient) {
    getAdapter().remove(recipient);
    updateViewState();
  }

  @Override
  public void onRecipientsPanelUpdate(List<Recipient> recipients) {
    if (recipients != null && !recipients.isEmpty()) addSelectedContacts(recipients);
  }

  //
  private void handleGroupCreate() {
    //- onclick validation
    /*if (getAdapter().getCount() < 1)
    {


      Log.i(TAG, getString(R.string.GroupCreateActivity_contacts_no_members));
      Toast.makeText(getApplicationContext(), R.string.GroupCreateActivity_contacts_no_members, Toast.LENGTH_SHORT).show();
      return;

    }*/

    // check for existing group
    String cfname = ufLocation.getInstance().getBaseLocationPrefix()+getGroupName();
    if (DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance()).isGroupAvailableByCname(cfname, true)) {
      Toast.makeText(getApplicationContext(), R.string.group_already_exists, Toast.LENGTH_SHORT).show();
      return;
    }

    if (isSignalGroup()) {
//      new CreateSignalGroupTask(this, masterSecret, avatarBmp, getGroupName(), getAdapter().getRecipients(), false).execute();
      new CreateSignalGroupTask(this, avatarBmp, getGroupName(), getAdapter().getRecipients()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    } else {
//      new CreateMmsGroupTask(this, masterSecret, getAdapter().getRecipients()).execute();
      new CreateMmsGroupTask(this, getAdapter().getRecipients()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }

  private void handleGroupUpdate() {
    new UpdateSignalGroupTask(this, groupToUpdate.get().id, avatarBmp,
                              getGroupName(), getAdapter().getRecipients(),
                              preExistingMembersAdapter.getRecipients())
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private void handleOpenConversation(long threadId, Recipient recipient) {
    Log.d(TAG, ">> handleOpenConversation: launching ConversationActivity on threadid: "+threadId);
    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, ThreadDatabase.DistributionTypes.DEFAULT);
    intent.putExtra(ConversationActivity.ADDRESS_EXTRA, recipient.getAddress());// this the GroupRecipient entity, not actual members
    startActivity(intent);
    finish();
  }

  private SelectedRecipientsAdapter getAdapter() {
    return (SelectedRecipientsAdapter)lv.getAdapter();
  }

  private @Nullable String getGroupName() {
    return groupName.getText() != null ? groupName.getText().toString() : null;
  }

  private float dp2px(float dp) {
    final float scale = getResources().getDisplayMetrics().density;
    return dp * scale + 0.5f;
  }

  @Override
  public void onActivityResult(int reqCode, int resultCode, final Intent data) {
    super.onActivityResult(reqCode, resultCode, data);
    Uri outputFile = Uri.fromFile(new File(getCacheDir(), "cropped"));

    if (data == null || resultCode != Activity.RESULT_OK)
      return;

    switch (reqCode) {
      case PICK_CONTACT:
        List<String> selected = data.getStringArrayListExtra("contacts");
        for (String contact : selected) {
          Address   address   = Address.fromExternal(this, contact);
          Recipient recipient = Recipient.from(this, address, false);

          addSelectedContacts(recipient);
        }
        break;

      case AvatarSelection.REQUEST_CODE_AVATAR:
        AvatarSelection.circularCropImage(this, data.getData(), outputFile, R.string.CropImageActivity_group_avatar);
        break;
      case AvatarSelection.REQUEST_CODE_CROP_IMAGE:
        final Uri resultUri = AvatarSelection.getResultUri(data);
        GlideApp.with(this)
                .asBitmap()
                .load(resultUri)
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .centerCrop()
                .override(AVATAR_SIZE, AVATAR_SIZE)
                .into(new SimpleTarget<Bitmap>() {
                  @Override
                  public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                    setAvatar(resultUri, resource);
                  }
                });
    }
  }

  private class AddRecipientButtonListener implements View.OnClickListener {

    // Added constructor with adapter. we need this in order to indicate members in the list that were already added
    private  SelectedRecipientsAdapter mPreExistingMembersAdapter;

    public AddRecipientButtonListener (SelectedRecipientsAdapter selectedRecipientsAdapter)
    {
      this.mPreExistingMembersAdapter = selectedRecipientsAdapter;
      Log.d (TAG, String.format("AddRecipientButtonListener: Preloading with '%d' members", this.mPreExistingMembersAdapter.getRecipientsList().size()));
    }
    //

    @Override
    public void onClick(View v) {
      Intent intent = new Intent(GroupCreateActivity.this, PushContactSelectionActivity.class);
      if (groupToUpdate.isPresent()) {
        intent.putExtra(ContactSelectionListFragment.DISPLAY_MODE, DisplayMode.FLAG_PUSH);
      } else {
        intent.putExtra(ContactSelectionListFragment.DISPLAY_MODE, DisplayMode.FLAG_PUSH | DisplayMode.FLAG_SMS);
      }

      // pass the pre existing contacts
      HashMap<String, Recipient> recipientsPreExisting = new HashMap<>();
      for (Recipient recipient: mPreExistingMembersAdapter.getRecipients()) {
        recipientsPreExisting.put(recipient.getAddress().serialize(), recipient);
        //Log.d (TAG, String.format("onCreateView: recipientsPreExisting: Added number:'%s', id:'%d'", recipient.getNumber(), recipient.getRecipientId()));
      }
      final Bundle bundle = new Bundle();
      bundle.putBinder(EXISTING_CONTACTS_EXTRA, new AdapterWrapperForBinder(recipientsPreExisting));
      intent.putExtras(bundle);
      //

      startActivityForResult(intent, PICK_CONTACT);
    }
  }

  //
  public class AdapterWrapperForBinder extends Binder
  {
    private final Object mData;

    public AdapterWrapperForBinder(Object data) {
      mData = data;
    }

    public Object getData() {
      return mData;
    }
  }
//

  private static class CreateMmsGroupTask extends AsyncTask<Void,Void,GroupActionResult> {
    private final GroupCreateActivity activity;
    private final Set<Recipient>      members;

    public CreateMmsGroupTask(GroupCreateActivity activity, Set<Recipient> members) {
      this.activity     = activity;
      this.members      = members;
    }

    @Override
    protected GroupActionResult doInBackground(Void... avoid) {
      List<Address> memberAddresses = new LinkedList<>();

      for (Recipient recipient : members) {
        memberAddresses.add(recipient.getAddress());
      }

      memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(activity)));

      String    groupId        = DatabaseFactory.getGroupDatabase(activity).getOrCreateGroupForMembers(memberAddresses, true);
      Recipient groupRecipient = Recipient.from(activity, Address.fromSerialized(groupId), true);
      long      threadId       = DatabaseFactory.getThreadDatabase(activity).getThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.DEFAULT);

      return new GroupActionResult(groupRecipient, threadId);
    }

    @Override
    protected void onPostExecute(GroupActionResult result) {
      activity.handleOpenConversation(result.getThreadId(), result.getGroupRecipient());
    }

    @Override
    protected void onProgressUpdate(Void... values) {
      super.onProgressUpdate(values);
    }
  }

  private abstract static class SignalGroupTask extends AsyncTask<Void,Void,Optional<GroupActionResult>> {
    protected GroupCreateActivity activity;
    protected Bitmap              avatar;
    protected Set<Recipient>      members;
    protected String              name;

    public SignalGroupTask(GroupCreateActivity activity,
                           Bitmap              avatar,
                           String              name,
                           Set<Recipient>      members)
    {
      this.activity     = activity;
      this.avatar       = avatar;
      this.name         = name;
      this.members      = members;
    }

    @Override
    protected void onPreExecute() {
      activity.findViewById(R.id.group_details_layout).setVisibility(View.GONE);
      activity.findViewById(R.id.creating_group_layout).setVisibility(View.VISIBLE);
      activity.findViewById(R.id.menu_create_group).setVisibility(View.GONE);
        final int titleResId = activity.groupToUpdate.isPresent()
                             ? R.string.GroupCreateActivity_updating_group
                             : R.string.GroupCreateActivity_creating_group;
        activity.creatingText.setText(activity.getString(titleResId, activity.getGroupName()));
      }

    @Override
    protected void onPostExecute(Optional<GroupActionResult> groupActionResultOptional) {
      if (activity.isFinishing()) return;
      activity.findViewById(R.id.group_details_layout).setVisibility(View.VISIBLE);
      activity.findViewById(R.id.creating_group_layout).setVisibility(View.GONE);
      activity.findViewById(R.id.menu_create_group).setVisibility(View.VISIBLE);
    }

  }

  //private static class CreateSignalGroupTask extends SignalGroupTask
  private class CreateSignalGroupTask extends SignalGroupTask //- static
  {
    int membersSize;
    GroupDatabase.PrivacyMode   privacyMode;
    GroupDatabase.DeliveryMode  deliveryMode;
    GroupDatabase.JoinMode      joinMode;
    RecipientDatabase.GroupPermission[] groupPermissions=new RecipientDatabase.GroupPermission[EnumFencePermissions.INVALID.getValue()-1];

    public CreateSignalGroupTask(GroupCreateActivity activity, Bitmap avatar, String name, Set<Recipient> members) {
      super(activity, avatar, name, members);
    }

    @Override
    protected void onPreExecute() {
      membersSize=numberPicker.getValue();
      privacyMode= GroupDatabase.PrivacyMode.values()[Integer.valueOf(privacyModeSelector.getSelectedItem().getValue())];
      deliveryMode= GroupDatabase.DeliveryMode.values()[Integer.valueOf(deliveryModeSelector.getSelectedItem().getValue())];
      joinMode= GroupDatabase.JoinMode.values()[Integer.valueOf(joinModeSelector.getSelectedItem().getValue())];

      groupPermissions[EnumFencePermissions.PRESENTATION.getValue()-1]  = new RecipientDatabase.GroupPermission(EnumFencePermissions.PRESENTATION, RecipientDatabase.EnumPermissionBaseList.NONE, RecipientDatabase.EnumPermissionListSemantics.values()[Integer.valueOf(semanticsModeSelector.getSelectedItem().getValue())]);
      groupPermissions[EnumFencePermissions.MEMBERSHIP.getValue()-1]    = new RecipientDatabase.GroupPermission(EnumFencePermissions.MEMBERSHIP, RecipientDatabase.EnumPermissionBaseList.NONE, RecipientDatabase.EnumPermissionListSemantics.values()[Integer.valueOf(semanticsModeSelector.getSelectedItem().getValue())]);
      groupPermissions[EnumFencePermissions.MESSAGING.getValue()-1]  = new RecipientDatabase.GroupPermission(EnumFencePermissions.MESSAGING, RecipientDatabase.EnumPermissionBaseList.NONE, RecipientDatabase.EnumPermissionListSemantics.values()[Integer.valueOf(semanticsModeSelector.getSelectedItem().getValue())]);
      groupPermissions[EnumFencePermissions.ATTACHING.getValue()-1]    = new RecipientDatabase.GroupPermission(EnumFencePermissions.ATTACHING, RecipientDatabase.EnumPermissionBaseList.NONE, RecipientDatabase.EnumPermissionListSemantics.values()[Integer.valueOf(semanticsModeSelector.getSelectedItem().getValue())]);
      groupPermissions[EnumFencePermissions.CALLING.getValue()-1]       = new RecipientDatabase.GroupPermission(EnumFencePermissions.CALLING, RecipientDatabase.EnumPermissionBaseList.NONE, RecipientDatabase.EnumPermissionListSemantics.values()[Integer.valueOf(semanticsModeSelector.getSelectedItem().getValue())]);
    }

    @Override
    protected Optional<GroupActionResult> doInBackground(Void... aVoid) {
      try {
        GroupActionResult result=GroupManager.createGroup(activity, members,
                                                          avatar, name, false,
                                                          ufLocation.getInstance().getBaseLocationPrefix(),
                                                          /*0.0, 0.0,*/
                                                          groupToUpdate.isPresent()
                                                            ?groupToUpdate.get().groupRecord.getFid()
                                                            :0,
                                                          membersSize,
                                                          GroupDatabase.GroupType.USER,
                                                          privacyMode,
                                                          deliveryMode,
                                                          joinMode,
                                                          groupPermissions);//
        if (result == null) return Optional.absent();
        else return Optional.of(result);
        //return Optional.of(GroupManager.createGroup(activity, masterSecret, members, avatar, name, isPublic, "cname", 0.0, 0.0, -1L ));//
      }
      //
      catch (FenceAlreadyExistsException ex)
      {
        return Optional.absent();
      }
    }

    @Override
    protected void onPostExecute(Optional<GroupActionResult> result) {
      if (result.isPresent() && result.get().getThreadId() > -1) {
        if (!activity.isFinishing()) {
          activity.handleOpenConversation(result.get().getThreadId(), result.get().getGroupRecipient());
        }
      } else {
        super.onPostExecute(result);
        Toast.makeText(activity.getApplicationContext(),
                       R.string.GroupCreateActivity_contacts_invalid_number, Toast.LENGTH_LONG).show();
      }
    }

  }


  private class UpdateSignalGroupTask extends SignalGroupTask
  {
    private String groupId;
    private Set<Recipient>  preExistingMembers;

    public UpdateSignalGroupTask(GroupCreateActivity activity,
                                 String groupId, Bitmap avatar, String name,
                                 Set<Recipient> members, Set<Recipient>preExistingMembers) // preExistingMembers
    {
      super(activity, avatar, name, members);
      this.groupId            = groupId;
      this.preExistingMembers = preExistingMembers;
    }

    @Override
    protected Optional<GroupActionResult> doInBackground(Void... aVoid) {
      try {
        return Optional.of(GroupManager.updateGroup(activity, groupId, members, avatar, name,
                                                    groupToUpdate.get().groupRecord, preExistingMembers,
                                                    activity.numberPicker.getValue(),
                                                    GroupDatabase.DeliveryMode.values()[Integer.valueOf(deliveryModeSelector.getSelectedItem().getValue())]));
      } catch (InvalidNumberException e) {
        return Optional.absent();
      }
    }

    @Override
    protected void onPostExecute(Optional<GroupActionResult> result) {
      if (result.isPresent() && result.get().getThreadId() > -1) {
        if (!activity.isFinishing()) {
          Intent intent = activity.getIntent();
          intent.putExtra(GROUP_THREAD_EXTRA, result.get().getThreadId());
          intent.putExtra(GROUP_ADDRESS_EXTRA, result.get().getGroupRecipient().getAddress());
          activity.setResult(RESULT_OK, intent);
          activity.finish();
        }
      } else {
        super.onPostExecute(result);
        Toast.makeText(activity.getApplicationContext(),
                       R.string.GroupCreateActivity_contacts_invalid_number, Toast.LENGTH_LONG).show();
      }
    }

  }

  private static class AddMembersTask extends AsyncTask<Recipient,Void,List<AddMembersTask.Result>> {
    static class Result {
      Optional<Recipient> recipient;
      boolean             isPush;
      String              reason;

      public Result(@Nullable Recipient recipient, boolean isPush, @Nullable String reason) {
        this.recipient = Optional.fromNullable(recipient);
        this.isPush    = isPush;
        this.reason    = reason;
      }
    }

    private GroupCreateActivity activity;
    private boolean             failIfNotPush;

    public AddMembersTask(@NonNull GroupCreateActivity activity) {
      this.activity      = activity;
      this.failIfNotPush = activity.groupToUpdate.isPresent();
    }

    @Override
    protected List<Result> doInBackground(Recipient... recipients) {
      final List<Result> results = new LinkedList<>();

      for (Recipient recipient : recipients) {
        boolean isPush = isActiveInDirectory(recipient);

        if (failIfNotPush && !isPush) {
          results.add(new Result(null, false, activity.getString(R.string.GroupCreateActivity_cannot_add_non_push_to_existing_group,
                                                                 recipient.toShortString())));
        } else if (TextUtils.equals(TextSecurePreferences.getUfsrvUserId(activity), recipient.getAddress().serialize())) {
          results.add(new Result(null, false, activity.getString(R.string.GroupCreateActivity_youre_already_in_the_group)));
        } else {
          results.add(new Result(recipient, isPush, null));
        }
      }
      return results;
    }

    @Override
    protected void onPostExecute(List<Result> results) {
      if (activity.isFinishing()) return;

      for (Result result : results) {
        if (result.recipient.isPresent()) {
          activity.getAdapter().add(result.recipient.get(), result.isPush);
        } else {
          Toast.makeText(activity, result.reason, Toast.LENGTH_SHORT).show();
        }
      }
      activity.updateViewState();
    }

  }


  private /*static*/class FillExistingGroupInfoAsyncTask extends ProgressDialogAsyncTask<String,Void,Optional<GroupData>> //- removed static
  {
    private GroupCreateActivity         activity;
    private SelectedRecipientsAdapter   mPreExistingMembersAdapter;//

    public FillExistingGroupInfoAsyncTask(GroupCreateActivity activity, SelectedRecipientsAdapter   preExistingMembersAdapter) {
      super(activity,
            R.string.GroupCreateActivity_loading_group_details,
            R.string.please_wait);
      this.activity = activity;
      this.mPreExistingMembersAdapter  = preExistingMembersAdapter;
    }

    @Override
    protected Optional<GroupData> doInBackground(String... groupIds) {
      final GroupDatabase         db               = DatabaseFactory.getGroupDatabase(activity);
      final List<Recipient>       recipients       = db.getGroupMembers(groupIds[0], false);
      final Optional<GroupRecord> group            = db.getGroup(groupIds[0]);
      final Set<Recipient>        existingContacts = new HashSet<>(recipients.size());

      final Recipient       groupRecipient   = Recipient.from(activity, Address.fromSerialized(group.get().getEncodedId()), false);//

      groupPreferences=DatabaseFactory.getRecipientDatabase(getContext())
                        .getRecipientSettings(groupRecipient.getAddress()).orNull();

      //
      SpannableStringBuilder builder;
      Optional<SpannableString> privateGroupName  =  new PrivateGroupForTwoName(getContext(), groupRecipient).getStylisedName();
      if (privateGroupName.isPresent())
      {
        builder   = new SpannableStringBuilder(privateGroupName.get());
        activity.isGroupNameEditable  = false;
      }
      else builder= new SpannableStringBuilder(groupRecipient.getDisplayName());
      builder.setSpan(new StyleSpan(Typeface.NORMAL), 0, builder.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
      //

      //editTextView.setEnabled(false);
      existingContacts.addAll(recipients);


      if (group.isPresent()) {
        return Optional.of(new GroupData(groupIds[0],
                                         existingContacts,
                                         BitmapUtil.fromByteArray(group.get().getAvatar()),
                                         group.get().getAvatar(),
                                         builder.toString(),//group.getTitle(),
                                          group.get()));// groupRecord
      } else {
        return Optional.absent();
      }
    }

    @Override
    protected void onPostExecute(Optional<GroupData> group) {
      super.onPostExecute(group);
      GroupRecord groupRecord = group.get().getGroupRecord();

      if (group.isPresent() && !activity.isFinishing()) {
        activity.groupToUpdate = group;
        activity.groupName.setText(group.get().name);

        if (groupRecord.getOwnerUserId()!=TextSecurePreferences.getUserId(getContext())) {

          boolean isUserPresentationPermitted = DatabaseFactory.getRecipientDatabase(getContext()).isUserPermitted(groupPreferences.getPermissionPresentation(), groupPreferences.getPermSemanticsPresentation(), TextSecurePreferences.getUserId(getContext()));
          if (!isUserPresentationPermitted) {
            avatar.setOnClickListener(null);
            groupName.setCursorVisible(false);
            groupName.setKeyListener(null);
            groupEditLayout.setError("");
            groupEditLayout.setHint("Group name cannot be changed.");
          }

          boolean isUserMembershipPermitted = DatabaseFactory.getRecipientDatabase(getContext())
                  .isUserPermitted(groupPreferences.getPermissionMembership(), groupPreferences.getPermSemanticsMembership(), TextSecurePreferences.getUserId(getContext()));
          if (!isUserMembershipPermitted) {
            recipientsPanel.disable();
            numberPicker.setEnabled(false);
          }

          //todo: these should be permissions driven

          deliveryModeSelector.setLocked(true);
          joinModeSelector.setLocked(true);
          privacyModeSelector.setLocked(true);
          semanticsModeSelector.setLocked(true);
        }

        {
          deliveryModeSelector.selectItemAt(groupRecord.getDeliveryType());
          joinModeSelector.selectItemAt(groupRecord.getJoinMode());
          privacyModeSelector.selectItemAt(groupRecord.getPrivacyMode());
          numberPicker.setValue(groupRecord.getMaxmembers());
          semanticsModeSelector.selectItemAt(groupPreferences.getPermSemanticsMembership().getValue()-1);//todo: this offset doesnt start at 0: update protobuf and other models
          //cannot be set after group creation
          semanticsModeSelector.setLocked(true);
        }

        if (group.get().avatarBmp != null) {
          activity.setAvatar(group.get().avatarBytes, group.get().avatarBmp);
        }

        mPreExistingMembersAdapter.setRecipients(group.get().recipients);//
        mPreExistingMembersAdapter.setOnRecipientDeletedListener(activity);//

        SelectedRecipientsAdapter adapter = new SelectedRecipientsAdapter(activity, group.get().recipients);
        adapter.setOnRecipientDeletedListener(activity);
        activity.lv.setAdapter(adapter);
        activity.updateViewState();
        activity.imageButton.setOnClickListener(new AddRecipientButtonListener(mPreExistingMembersAdapter));// instantiate new listener + added adapter to update panel with pre-existing members
      }
    }

  }

  private <T> void setAvatar(T model, Bitmap bitmap) {
    avatarBmp = bitmap;
    GlideApp.with(this)
            .load(model)
            .circleCrop()
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .into(avatar);
  }

  private static class GroupData {
    String        id;
    Set<Recipient> recipients;
    Bitmap         avatarBmp;
    byte[]         avatarBytes;
    String         name;
    // kind of bad to expose that low level abstraction here.. revise later
    GroupRecord     groupRecord;

    public GroupData(String  id, Set<Recipient> recipients, Bitmap avatarBmp, byte[] avatarBytes, String name, GroupRecord groupRecord) {//  groupRecord
      this.id          = id;
      this.recipients  = recipients;
      this.avatarBmp   = avatarBmp;
      this.avatarBytes = avatarBytes;
      this.name        = name;
      this.groupRecord       = groupRecord;//
    }

    public GroupRecord getGroupRecord ()
    {
      return groupRecord;
    }
  }
}
