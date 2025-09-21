package org.thoughtcrime.securesms.preferences.widgets;

import com.unfacd.android.R;

import android.content.Context;
import android.os.Build;
import androidx.annotation.RequiresApi;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.thoughtcrime.securesms.contacts.avatars.ProfileContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ResourceContactPhoto;
import org.thoughtcrime.securesms.conversation.colors.AvatarColor;
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

public class ProfilePreference extends Preference {

  private ImageView avatarView;
  private TextView  profileNameView;
  private TextView profileSubtextView;

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  public ProfilePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initialize();
  }

  public ProfilePreference(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  public ProfilePreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public ProfilePreference(Context context) {
    super(context);
    initialize();
  }

  private void initialize() {
    setLayoutResource(R.layout.profile_preference_view);
  }

  @Override
  public void onBindViewHolder(PreferenceViewHolder viewHolder) {
    super.onBindViewHolder(viewHolder);
    avatarView         = (ImageView)viewHolder.findViewById(R.id.avatar);
    profileNameView    = (TextView)viewHolder.findViewById(R.id.profile_name);
    profileSubtextView = (TextView)viewHolder.findViewById(R.id.number);

    refresh();
  }

  public void refresh() {
    if (profileSubtextView  == null) return;

    final Recipient self        = Recipient.self();
    final String  profileName   = self.getUfsrvUname();//AA+

    GlideApp.with(getContext().getApplicationContext())
            .load(new ProfileContactPhoto(self, self.getAvatarUfsrvId()))//AA++ self
            .error(new ResourceContactPhoto(R.drawable.ic_camera_solid_white_24).asDrawable(getContext(), AvatarColor.UNKNOWN))
            .circleCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(avatarView);

    if (!TextUtils.isEmpty(profileName)) {
      profileNameView.setText(profileName);
    }

    profileSubtextView.setText(self.getUfsrvUid());//AA++
//    profileSubtextView.setText(self.getUsername().map(username -> "@" + username).orElse(self.getE164().map(PhoneNumberFormatter::prettyPrint)).orElse(null));
  }
}