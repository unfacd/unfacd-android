package com.unfacd.android.ui.components.rainview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.TypedValue;
import android.view.ViewGroup;

import com.unfacd.android.ApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Modified from https://github.com/corerzhang/RainView
 */
public class SurfaceRainController implements IRainController {

    private static final float ITEM_COUNT = 200;
    private static final int MAX_DELAY = 5000;
    private static final int MAX_INCREMENT = 20;
    List<IRainItem> mRainItems = new ArrayList<>();
    ArrayList<Bitmap> drawableBitmaps;
    float mProgress;
    boolean isAllOver;


    public SurfaceRainController(int[] drawables) {
        for (int drawable : drawables) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            options.inSampleSize = RainItem.calculateInSampleSize(options, 100, 100);
            options.inJustDecodeBounds = false;
            drawableBitmaps.add(BitmapFactory.decodeResource(ApplicationContext.getInstance().getResources(), drawable, options));
        }

    }

    public SurfaceRainController(Bitmap[] drawables) {
        drawableBitmaps = new ArrayList(Arrays.asList(drawables));
    }

    public SurfaceRainController(ArrayList<Bitmap> drawables) {
        drawableBitmaps = drawables;
    }

    @Override
    public void handleLoadItem(ViewGroup parent) {
        mProgress = 0;
        if (mRainItems != null && mRainItems.size() > 0) {
            return;
        }
        int width = parent.getMeasuredWidth(), height = parent.getMeasuredHeight();

        Random random = new Random();
        for (int i = 0; i < ITEM_COUNT; i++) {

            int startX = random.nextInt(width);
            int startY = 0;

            int delay = random.nextInt(MAX_DELAY); //item延迟出现的时长
            int speed = random.nextInt(10) + MAX_INCREMENT; //item每次的y轴偏移量
            Bitmap bitmap = drawableBitmaps.get(random.nextInt(drawableBitmaps.size()));
            mRainItems.add(new SurfaceRainItem(parent.getContext(), bitmap, startX, startY, height, speed, delay));
        }
    }

    @Override
    public boolean handleOnDraw(Canvas canvas, int width, int height) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        canvas.save();
        if (mRainItems != null && mRainItems.size() > 0) {
            for (IRainItem mRainItem : mRainItems) {
                mRainItem.draw(canvas);
            }
        }
        canvas.restore();
        //Determine whether to terminate the drawing of the item
        boolean isAllOver = true;
        float allProgress = 0;
        if (mRainItems != null && mRainItems.size() > 0) {
            for (IRainItem mRainItem : mRainItems) {
                allProgress = mRainItem.progress() + allProgress;
                boolean isItemOut = mRainItem.isOut();
                if (!isItemOut) {
                    isAllOver = false;
                }
            }
        }
        mProgress = allProgress / ITEM_COUNT;
        this.isAllOver = isAllOver;
        return isAllOver;

    }

    @Override
    public boolean isOver() {
        return isAllOver;
    }

    @Override
    public float progress() {
        return mProgress;
    }

    @Override
    public void reset() {
        if (mRainItems != null && mRainItems.size() > 0) {
            for (IRainItem mRainItem : mRainItems) {
                mRainItem.reset();
            }
        }
        isAllOver = false;
    }

    @Override
    public void clear() {
        if (mRainItems != null && mRainItems.size() > 0) {
            for (IRainItem mRainItem : mRainItems) {
                mRainItem.clear();
            }
            mRainItems.clear();
        }
        isAllOver = false;
    }

}
