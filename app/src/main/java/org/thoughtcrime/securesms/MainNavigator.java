package org.thoughtcrime.securesms;

import android.app.Activity;
import android.content.Intent;

import com.unfacd.android.R;
import com.unfacd.android.ui.FenceMapActivity;

import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity;
import org.thoughtcrime.securesms.conversation.ConversationIntents;
import org.thoughtcrime.securesms.conversationlist.ConversationListFragment;
import org.thoughtcrime.securesms.groups.ui.creategroup.CreateGroupActivity;
import org.thoughtcrime.securesms.insights.InsightsLauncher;
import org.thoughtcrime.securesms.profiles.manage.ManageProfileActivity;
import org.thoughtcrime.securesms.recipients.RecipientId;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import static org.thoughtcrime.securesms.conversationlist.ConversationListFragment.INTROCONTACT_MSGID;

public class MainNavigator {

  public static final int REQUEST_CONFIG_CHANGES = 901;

  private final MainActivity activity;

  public MainNavigator(@NonNull MainActivity activity) {
    this.activity = activity;

  }

  public static MainNavigator get(@NonNull Activity activity) {
    if (!(activity instanceof MainActivity)) {
      throw new IllegalArgumentException("Activity must be an instance of MainActivity!");
    }

    return ((MainActivity) activity).getNavigator();
  }

  /**
   * @return True if the back pressed was handled in our own custom way, false if it should be given
   *         to the system to do the default behavior.
   */
  public boolean onBackPressed() {
    Fragment fragment = getFragmentManager().findFragmentById(R.id.fragment_container);

    if (fragment instanceof BackHandler) {
      return ((BackHandler) fragment).onBackPressed();
    }

    return false;
  }

  //AA+ IntroContact builder dialog requires Activity context so we need this plumbing
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    Fragment fragment = getFragmentManager().findFragmentById(R.id.fragment_container);

    if (fragment instanceof ConversationListFragment) {
      fragment.onActivityResult(requestCode, resultCode, data);
    }
  }

  public void goToConversation(@NonNull RecipientId recipientId, long threadId, int distributionType, int startingPosition, long fid) {//AA+ fid
    Intent intent = ConversationIntents.createBuilder(activity, recipientId, threadId)
            .withDistributionType(distributionType)
            .withStartingPosition(startingPosition)
            .withFid(fid)//AA+
            .build();

    activity.startActivity(intent);
    activity.overridePendingTransition(R.anim.slide_from_end, R.anim.fade_scale_out);
  }

  public void goToAppSettings() {
    activity.startActivityForResult(AppSettingsActivity.home(activity), REQUEST_CONFIG_CHANGES);
  }
  //AA ORIG TBD
//  public void goToAppSettings() {
//    Intent intent = new Intent(activity, ApplicationPreferencesActivity.class);
//    activity.startActivityForResult(intent, REQUEST_CONFIG_CHANGES);
//  }

  //AA+
  public void goToProfileEditing() {
    activity.startActivity(ManageProfileActivity.getIntentForUsernameEdit(activity));
  }

  public void goToGroupCreation() {
    activity.startActivity(CreateGroupActivity.newIntent(activity));
  }

  public void goToInvite() {
    Intent intent = new Intent(activity, InviteActivity.class);
    activity.startActivity(intent);
  }

  public void goToInsights() {
    InsightsLauncher.showInsightsDashboard(activity.getSupportFragmentManager());
  }

  //AA+
  public void goToGroupsNearby() {
    Intent intent = new Intent(activity, FenceMapActivity.class);
    activity.startActivity(intent);
  }

  public void goToPairedGroupCreation() {
    Intent intent = new Intent(activity, NewConversationActivity.class);
    activity.startActivity(intent);

//    activity.startActivity(newIntentForPairedGroup(activity));
  }
  //

  private @NonNull FragmentManager getFragmentManager() {
    return activity.getSupportFragmentManager();
  }

  public boolean isLaunchedWithIntroContact ()
  {
    return activity.getIntent().getLongExtra(INTROCONTACT_MSGID, -1) > 0;
  }

  public MainActivity getActivity ()
  {
    return activity;
  }

  public enum ConversationListViews {

    Open("OPEN", R.layout.conversation_list_pager_fragment),
    Invite("INVITE", R.layout.conversation_list_pager_fragment),
    Left("LEFT", R.layout.conversation_list_pager_fragment),
    Guardian("GUARDIAN", R.layout.conversation_list_pager_fragment);


    private String name;
    private int mLayoutResId;

    ConversationListViews (String name, int layoutResId) {
      this.name = name;
      mLayoutResId = layoutResId;
    }

    public String getName() {
      return this.name;
    }

    public int getLayoutResId() {
      return mLayoutResId;
    }

  }
  //

  public interface BackHandler {
    /**
     * @return True if the back pressed was handled in our own custom way, false if it should be given
     *         to the system to do the default behavior.
     */
    boolean onBackPressed();
  }
}