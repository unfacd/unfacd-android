package com.unfacd.android.ui.components.rainview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.TypedValue;
import android.view.ViewGroup;

import com.unfacd.android.ApplicationContext;

import java.util.ArrayList;

/**
 * Modified from https://github.com/corerzhang/RainView
 */
public interface IRainController {

    int dp50Pixel = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50, ApplicationContext.getInstance().getResources().getDisplayMetrics());

    static int getDp50Pixel()
    {
        return dp50Pixel;
    }

    void handleLoadItem (ViewGroup parent);

    boolean handleOnDraw (Canvas canvas, int width, int height);

    void clear ();

    void reset ();

    float progress ();

    boolean isOver ();

}
