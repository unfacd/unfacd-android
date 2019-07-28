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

package com.unfacd.android.ui.hostfrgament;

import com.unfacd.android.R;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

//This framework allows for fragments hosted inside tabbed group to manage their own backstack
//https://github.com/marktani/tabbed-viewpager-example and https://medium.com/@nilan/separate-back-navigation-for-a-tabbed-view-pager-in-android-459859f607e4
//https://stackoverflow.com/questions/7723964/replace-fragment-inside-a-viewpager
public class HostFragment extends BackStackFragment {
  private Fragment fragment;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    View view = inflater.inflate(R.layout.host_fragment, container, false);
    if (fragment != null) {
      replaceFragment(fragment, false);
    }
    return view;
  }

  public void replaceFragment(Fragment fragment, boolean addToBackstack) {
    if (addToBackstack) {
      getChildFragmentManager().beginTransaction().replace(R.id.hosted_fragment, fragment).addToBackStack(null).commit();
    } else {
      getChildFragmentManager().beginTransaction().replace(R.id.hosted_fragment, fragment).commit();
    }
  }

  public static HostFragment newInstance(Fragment fragment) {
    HostFragment hostFragment = new HostFragment();
    hostFragment.fragment = fragment;
    return hostFragment;
  }

  public Fragment getHostedFragment () {
    return fragment;
  }
}