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

package com.unfacd.android.preferences;

import android.app.Dialog;
import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialog;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.preference.PreferenceDialogFragmentCompat;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public abstract class PreferenceDialogFragment extends PreferenceDialogFragmentCompat {

  @Override
  protected View onCreateDialogView(Context context) {
    Context context2 = new AlertDialog.Builder(context).getContext();
    return super.onCreateDialogView(context2);
  }

  /**
   * Copied from {@link AppCompatDialogFragment}.
   *
   * @hide
   */
  @SuppressWarnings("RestrictedApi")
  @Override
  public void setupDialog(Dialog dialog, int style) {
    if (dialog instanceof AppCompatDialog) {
      // If the dialog is an AppCompatDialog, we'll handle it
      AppCompatDialog acd = (AppCompatDialog) dialog;
      switch (style) {
        case STYLE_NO_INPUT:
          dialog.getWindow().addFlags(
                  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
          // fall through...
        case STYLE_NO_FRAME:
        case STYLE_NO_TITLE:
          acd.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
      }
    } else {
      // Else, just let super handle it
      super.setupDialog(dialog, style);
    }
  }
}