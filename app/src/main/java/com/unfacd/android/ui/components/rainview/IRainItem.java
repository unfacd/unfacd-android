package com.unfacd.android.ui.components.rainview;

import android.graphics.Canvas;

/**
 * Modified from https://github.com/corerzhang/RainView
 */
public interface IRainItem {

    int DEFAULT_SCALE_MIN_PERCENT = 80;
    int DEFAULT_SCALE_MAX_PERCENT = 100;

    void draw (Canvas canvas);

    void reset ();

    boolean isOut ();

    float progress ();

    void clear ();

}
