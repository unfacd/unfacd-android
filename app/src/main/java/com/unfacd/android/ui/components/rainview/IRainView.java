package com.unfacd.android.ui.components.rainview;

import android.app.Activity;

/**
 * Modified from https://github.com/corerzhang/RainView
 */
public interface IRainView {


    void startRain (Activity activity);
    void stopRain ();
    void setRainCallback (RainCallback callback);
    void setRainController (IRainController controller);

     interface RainCallback {
        void onRainStart ();

        void onRainProgress (float progress);

        void onRainEnd ();
    }

    class RainCallbackAdapter implements RainCallback {
        @Override
        public void onRainStart() {
        }

        @Override
        public void onRainProgress(float progress) {

        }

        @Override
        public void onRainEnd() {
        }
    }
}
