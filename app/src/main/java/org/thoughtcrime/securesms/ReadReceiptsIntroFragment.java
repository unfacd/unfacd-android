package org.thoughtcrime.securesms;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.appcompat.widget.SwitchCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.MultiDeviceConfigurationUpdateJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.ViewUtil;

public class ReadReceiptsIntroFragment extends Fragment {

  public static ReadReceiptsIntroFragment newInstance() {
    ReadReceiptsIntroFragment fragment = new ReadReceiptsIntroFragment();
    Bundle args = new Bundle();
    fragment.setArguments(args);
    return fragment;
  }

  public ReadReceiptsIntroFragment() {}

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View         v          = inflater.inflate(R.layout.experience_upgrade_preference_fragment, container, false);
    SwitchCompat preference = v.findViewById(R.id.preference);

    preference.setChecked(TextSecurePreferences.isReadReceiptsEnabled(getContext()));
    preference.setOnCheckedChangeListener((buttonView, isChecked) -> {
      TextSecurePreferences.setReadReceiptsEnabled(getContext(), isChecked);
      ApplicationDependencies.getJobManager()
              .add(new MultiDeviceConfigurationUpdateJob(isChecked,
                                                         TextSecurePreferences.isTypingIndicatorsEnabled(requireContext()),
                                                         TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(getContext()),
                                                         SignalStore.settings().isLinkPreviewsEnabled()));
    });

    return v;
  }
}