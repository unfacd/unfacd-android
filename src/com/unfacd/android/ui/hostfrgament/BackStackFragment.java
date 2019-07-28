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

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

public abstract class BackStackFragment extends Fragment {
  public static boolean handleBackPressed(FragmentManager fm)
  {
    if(fm.getFragments() != null){
      for(Fragment frag : fm.getFragments()){
        if(frag != null && frag.isVisible() && frag instanceof BackStackFragment){
          if(((BackStackFragment)frag).onBackPressed()){
            return true;
          }
        }
      }
    }
    return false;
  }

  protected boolean onBackPressed()
  {
    FragmentManager fm = getChildFragmentManager();
    if(handleBackPressed(fm)){
      return true;
    } else if(getUserVisibleHint() && fm.getBackStackEntryCount() > 0){
      fm.popBackStack();
      return true;
    }
    return false;
  }
}