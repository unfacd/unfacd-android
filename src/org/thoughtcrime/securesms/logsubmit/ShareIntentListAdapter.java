package org.thoughtcrime.securesms.logsubmit;

import com.unfacd.android.R;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/**
 * rhodey
 */
public class ShareIntentListAdapter extends ArrayAdapter<ResolveInfo> {

  public static ShareIntentListAdapter getAdapterForIntent(Context context, Intent shareIntent) {
    List<ResolveInfo> activities = context.getPackageManager().queryIntentActivities(shareIntent, 0);
    return new ShareIntentListAdapter(context, activities.toArray(new ResolveInfo[activities.size()]));
  }

  public ShareIntentListAdapter(Context context, ResolveInfo[] items) {
    super(context, R.layout.share_intent_list, items);
  }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    LayoutInflater  inflater    = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    View            rowView     = inflater.inflate(R.layout.share_intent_row, parent, false);
    ImageView       intentImage = (ImageView) rowView.findViewById(R.id.share_intent_image);
    TextView        intentLabel = (TextView)  rowView.findViewById(R.id.share_intent_label);

    ApplicationInfo intentInfo = getItem(position).activityInfo.applicationInfo;

    intentImage.setImageDrawable(intentInfo.loadIcon(getContext().getPackageManager()));
    intentLabel.setText(intentInfo.loadLabel(getContext().getPackageManager()));

    return rowView;
  }

}