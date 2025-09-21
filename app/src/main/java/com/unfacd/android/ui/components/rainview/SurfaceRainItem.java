package com.unfacd.android.ui.components.rainview;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.TypedValue;

import com.unfacd.android.ApplicationContext;

import java.util.Random;


/**
 * Created by corer.zhang on 16/8/23.
 */
public class SurfaceRainItem implements IRainItem {
    int mItemHeight;
    int mItemWidth;
    Bitmap mBitmap;
    Paint mPaint;

    float mStartX;
    float mStartY;
    float mEndY;
    int mDelay;

    int mTimePass;

    float mSpeed;

    float mProgress;

    float mCurrentY;

    float mStartTime;

    float mLength;

    float scale;

    public SurfaceRainItem(Context context, int resId, int startX, int startY, int endY, float speed, int delay) {

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(context.getResources(), resId, options);
        options.inSampleSize = calculateInSampleSize(options, 60, 60); // 计算inSampleSize
        options.inJustDecodeBounds = false;
        mBitmap = BitmapFactory.decodeResource(context.getResources(), resId,options);

        mItemHeight = mBitmap.getHeight();
        mItemWidth = mBitmap.getWidth();
        mStartX = startX;
        mStartY = startY - mItemHeight;
        mEndY = endY + mItemHeight;

        mLength = mEndY - mStartY;

        mCurrentY = mStartY;
        mSpeed = speed;
        mDelay = delay;

        scale = 1f * (new Random().nextInt(DEFAULT_SCALE_MAX_PERCENT - DEFAULT_SCALE_MIN_PERCENT + 1) + DEFAULT_SCALE_MIN_PERCENT) / 100;

        mPaint = new Paint();
    }

    public SurfaceRainItem(Context context, Bitmap bitmap, int startX, int startY, int endY, float speed, int delay) {
        mBitmap = bitmap;
        mItemHeight = mBitmap.getHeight();
        mItemWidth = mBitmap.getWidth();
        mStartX = startX;
        mStartY = startY - mItemHeight;
        mEndY = endY + mItemHeight;

        mLength = mEndY - mStartY;

        mCurrentY = mStartY;
        mSpeed = speed;
        mDelay = delay;

        scale = 1f * (new Random().nextInt(DEFAULT_SCALE_MAX_PERCENT - DEFAULT_SCALE_MIN_PERCENT + 1) + DEFAULT_SCALE_MIN_PERCENT) / 100;

        mPaint = new Paint();
    }

    @Override
    public void draw(Canvas canvas) {
        if (mBitmap == null) {
            return;
        }

        long currentTime = SystemClock.elapsedRealtime();
        if (mStartTime == 0) {
            mStartTime = currentTime;
        }
        float deltaTime = currentTime - mStartTime;

        if (deltaTime <= mDelay) {
            return;
        }

        //already drawn
        if (mCurrentY >= mEndY) {
            mProgress = 1;
            return;
        }

        mCurrentY = mCurrentY + mSpeed;

        mProgress = Math.min(mCurrentY >= 0 ? (mCurrentY) / mLength : 0, 1);

        Matrix matrix = new Matrix();
        final int dp50Pixel = IRainController.getDp50Pixel();
        float heightScale = dp50Pixel / mBitmap.getHeight();
        float widthScale = dp50Pixel / mBitmap.getWidth();
        matrix.setScale(widthScale * scale, heightScale * scale);
        matrix.postTranslate(mStartX, mCurrentY);

//        canvas.drawBitmap(mBitmap, mStartX, mCurrentY, mPaint);
        canvas.drawBitmap(mBitmap, matrix, mPaint);

    }

    @Override
    public boolean isOut() {
        return mCurrentY >= mEndY;
    }


    @Override
    public float progress() {
        return mProgress;
    }

    @Override
    public void reset() {
        mProgress = 0;
        mTimePass = 0;
        mStartTime = 0;
        mCurrentY = mStartY;
    }

    @Override
    public void clear() {
        if (mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options,
                                             int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) > reqHeight
                    && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

}
