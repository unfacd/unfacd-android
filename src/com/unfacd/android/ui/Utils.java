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

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.io.FileOutputStream;

public class Utils
{

  public static Activity getHostActivity (Context context) {
    while (context instanceof ContextWrapper) {
      if (context instanceof Activity) {
        return (Activity)context;
      }
      context = ((ContextWrapper)context).getBaseContext();
    }
    return null;
  }

  //from within the context of as fragment
  public static Activity getActivityForFragment (Context context) {
    if (context == null) {
      return null;
    }
    if (context instanceof Activity) {
      return (Activity) context;
    } else if (context instanceof ContextWrapper) {
      return getActivityForFragment(((ContextWrapper) context).getBaseContext());
    }

    return null;
  }

  public static Integer increaseBrightnessByPercentage(Integer color, int value) {

    int red = Color.red(color);
    int green = Color.green(color);
    int blue = Color.blue(color);

    red = (int) (red + (red/100.0 * value));
    green = (int) (green + (green/100.0 * value));
    blue = (int) (blue + (blue/100.0 * value));

    return Color.argb(155, red, green, blue);
  }

  public static int adjustAlpha(int color, float factor) {
    int alpha = Math.round(Color.alpha(color) * factor);
    int red = Color.red(color);
    int green = Color.green(color);
    int blue = Color.blue(color);
    return Color.argb(alpha, red, green, blue);
  }

  public static int darken(int color, double fraction) {
    int red = Color.red(color);
    int green = Color.green(color);
    int blue = Color.blue(color);
    red = darkenColor(red, fraction);
    green = darkenColor(green, fraction);
    blue = darkenColor(blue, fraction);
    int alpha = Color.alpha(color);

    return Color.argb(alpha, red, green, blue);
  }

  private static int darkenColor(int color, double fraction) {
    return (int) Math.max(color - (color * fraction), 0);
  }

  /**
   * Sets the Typeface e.g. Roboto-Thin.tff for an Activity
   *
   * @param container parent View containing the TextViews
   * @param font      Typeface to set
   * public static final View setFontForFragment(Context context, View root) {
  GDataPreferences prefs = new GDataPreferences(context);
  Typeface font = TypeFaces.getTypeFace(context, prefs.getApplicationFont());
  setFontToLayouts(root, font);
  return root;
  }
   */
  public static final void setFontToLayouts(Object container, Typeface font) {
    if (container == null || font == null) return;

    if (container instanceof View) {
      if (container instanceof TextView) {
        ((TextView) container).setTypeface(font);
      } else if (container instanceof LinearLayout) {
        final int count = ((LinearLayout) container).getChildCount();
        for (int i = 0; i <= count; i++) {
          final View child = ((LinearLayout) container).getChildAt(i);
          if (child instanceof TextView) {
            // Set the font if it is a TextView.
            ((TextView) child).setTypeface(font);
          } else if (child instanceof ViewGroup) {
            // Recursively attempt another ViewGroup.
            setFontToLayouts(child, font);
          }
        }
      } else if (container instanceof FrameLayout) {
        final int count = ((FrameLayout) container).getChildCount();
        for (int i = 0; i <= count; i++) {
          final View child = ((FrameLayout) container).getChildAt(i);
          if (child instanceof TextView) {
            ((TextView) child).setTypeface(font);
          } else if (child instanceof ViewGroup) {
            setFontToLayouts(child, font);
          }
        }
      } else if (container instanceof RelativeLayout) {
        final int count = ((RelativeLayout) container).getChildCount();
        for (int i = 0; i <= count; i++) {
          final View child = ((RelativeLayout) container).getChildAt(i);
          if (child instanceof TextView) {
            ((TextView) child).setTypeface(font);
          } else if (child instanceof ViewGroup) {
            setFontToLayouts(child, font);
          }
        }
      }

    } else if (container instanceof ViewGroup) {
      final int count = ((ViewGroup) container).getChildCount();
      for (int i = 0; i <= count; i++) {
        final View child = ((ViewGroup) container).getChildAt(i);
        if (child instanceof TextView) {
          ((TextView) child).setTypeface(font);
        } else if (child instanceof ViewGroup) {
          setFontToLayouts(child, font);
        }
      }
    }
  }


  public static Bitmap convertDrawableToBitmap(Drawable drawable, int widthPixels, int heightPixels) {
    Bitmap mutableBitmap = Bitmap.createBitmap(widthPixels, heightPixels, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(mutableBitmap);
    drawable.setBounds(0, 0, widthPixels, heightPixels);
    drawable.draw(canvas);

    return mutableBitmap;
  }

  public void writeJpegImageToFile(Bitmap bitmap, FileOutputStream jpegFileStream) {
    // use JPEG quality of 80 (scale 1 - 100)
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpegFileStream);
  }
}
