/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.nfc;

import com.android.nfc3.R;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class P2pAnimationActivity extends Activity implements Handler.Callback,
        AnimatorUpdateListener, View.OnTouchListener  {
    private static final float INITIAL_SCREENSHOT_SCALE = 0.7f;
    private static final float FINAL_SCREENSHOT_SCALE = 0.0f;

    private static final int MSG_RESULT_FAILURE = 4;
    private static final int MSG_RESULT_SEND = 5;
    private static final int MSG_RESULT_RECEIVE = 6;


    private static final int RESULT_WAITING = 0;
    private static final int RESULT_FAILURE = 1;
    private static final int RESULT_SEND = 2;
    private static final int RESULT_RECV = 3;

    private static int mResult;

    Context mContext;
    LayoutInflater mLayoutInflater;
    View mScreenshotLayout;
    ImageView mScreenshotView;
    ImageView mClonedView;
    TextView mShareText;

    int mScreenshotWidth;

    StartAnimationListener mStartListener;
    EndAnimationListener mEndListener;

    // Start animator, always played
    ValueAnimator mStartAnimator;

    // Send only animation
    AnimatorSet mSendAnimatorSet;
    ValueAnimator mScaleDownAnimator;
    ValueAnimator mScaleUpAnimator;

    // Receive animation
    ValueAnimator mReceiveAnimator;

    // Failure animation
    ValueAnimator mFailureAnimator;

    // Down interpolators
    DecelerateInterpolator mScaleDownInterpolator;
    DecelerateInterpolator mAlphaDownInterpolator;
    DecelerateInterpolator mOffsetInterpolator;

    // Up interpolators
    AccelerateInterpolator mScaleUpInterpolator;
    AccelerateInterpolator mAlphaUpInterpolator;
    AccelerateInterpolator mCloneScaleDownInterpolator;


    // These are all read/written on the UI thread, so no
    // need to synchronize these.
    // TODO state var could clean this up a lot.
    boolean mWaitingForResult = true;
    boolean mStartAnimDone = false;
    boolean mEndRequested = false;
    static Bitmap sScreenBitmap;
    static P2pEventListener.Callback sCallback;
    static Handler sHandler;

    // These are initialized by calls to the static method createScreenshot()
    // and are synchronized on P2pAnimationActivity.class
    static Display mDisplay;
    static DisplayMetrics mDisplayMetrics;
    static Matrix mDisplayMatrix;
    static WindowManager mWindowManager;

    class StartAnimationListener extends AnimatorListenerAdapter {
        @Override
        // Note that this will be called on the UI thread!
        public void onAnimationEnd(Animator animation) {
            if (mEndRequested) {
                // Ended on request, don't start follow-up anim
                // and get rid of the view
            } else {
                mStartAnimDone = true;
                if (!mWaitingForResult) { // Result already in
                    playEndAnimation(mResult);
                } //  else, wait for it
            }
        }
    }

    class EndAnimationListener extends AnimatorListenerAdapter {
        @Override
        public void onAnimationEnd(Animator animation) {
            finish();
        }
    }

    void playEndAnimation(int result) {
        switch (result) {
            case RESULT_SEND:
                mSendAnimatorSet.start();
                break;
            case RESULT_RECV:
                mReceiveAnimator.start();
                break;
            case RESULT_FAILURE:
                mFailureAnimator.start();
                break;
        }
    }

    ValueAnimator getFloatAnimation(int duration, AnimatorUpdateListener updateListener,
            AnimatorListener listener) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(null);
        anim.setDuration(duration);
        if (updateListener != null) {
            anim.addUpdateListener(updateListener);
        }
        if (listener != null) {
            anim.addListener(listener);
        }

        return anim;
    }

    void createAnimators() {
        mStartListener = new StartAnimationListener();
        mEndListener = new EndAnimationListener();

        mStartAnimator = getFloatAnimation(500, this, mStartListener);

        mScaleDownAnimator = getFloatAnimation(500, this, null);
        mScaleUpAnimator = getFloatAnimation(500, this, null);
        // Combine the two in a set
        mSendAnimatorSet = new AnimatorSet();
        List<Animator> animList = new ArrayList<Animator>();
        animList.add(mScaleDownAnimator);
        animList.add(mScaleUpAnimator);
        mSendAnimatorSet.playSequentially(animList);
        mSendAnimatorSet.addListener(mEndListener);

        mFailureAnimator = getFloatAnimation(500, this, mEndListener);
        mReceiveAnimator = getFloatAnimation(200, this, mEndListener);

        mScaleDownInterpolator = new DecelerateInterpolator(1.5f);
        mAlphaDownInterpolator = new DecelerateInterpolator(1f);
        mOffsetInterpolator = new DecelerateInterpolator(1.5f);

        mScaleUpInterpolator = new AccelerateInterpolator(1.5f);
        mAlphaUpInterpolator = new AccelerateInterpolator(1.5f);
        mCloneScaleDownInterpolator = new AccelerateInterpolator(1.0f);

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sHandler = new Handler(this);

        // Inflate the screenshot layout
        mLayoutInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mScreenshotLayout = mLayoutInflater.inflate(R.layout.screenshot, null);
        mScreenshotView = (ImageView) mScreenshotLayout.findViewById(R.id.screenshot);
        mClonedView = (ImageView) mScreenshotLayout.findViewById(R.id.clone);
        mShareText = (TextView) mScreenshotLayout.findViewById(R.id.calltoaction);
        mScreenshotView.setOnTouchListener(this);
        mClonedView.setOnTouchListener(this);

        setContentView(mScreenshotLayout);

        createAnimators();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Do nothing to ignore orientation changes
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (sScreenBitmap != null) {
            mClonedView.setImageBitmap(sScreenBitmap);
            mClonedView.setVisibility(View.GONE);
            mScreenshotView.setImageBitmap(sScreenBitmap);
            mScreenshotWidth = sScreenBitmap.getWidth();

            startAnimating();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mStartAnimator != null) {
            mStartAnimator.end();
        }
        if (mSendAnimatorSet != null) {
            mSendAnimatorSet.end();
        }
        if (mReceiveAnimator != null) {
            mReceiveAnimator.end();
        }
        if (mFailureAnimator != null) {
            mFailureAnimator.end();
        }
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     *
     * Must be called from the UI thread.
     */
    public void startAnimating() {
        // At this point no anims are running, no need to sync these
        mResult = RESULT_WAITING;
        mWaitingForResult = true;
        mStartAnimDone = false;
        mEndRequested = false;

        mStartAnimator.start();
    }

    /**
     * Finalizes the running animation with a failure animation.
     */
    public static void finishWithFailure() {
        if (sHandler != null) {
            sHandler.sendEmptyMessage(MSG_RESULT_FAILURE);
        }
   }

    /**
     * Finalizes the running animation with the send animation.
     */
    public static void finishWithSend() {
        if (sHandler != null) {
            sHandler.sendEmptyMessage(MSG_RESULT_SEND);
        }
    }

    /**
     * Finalizes the running animation with the received animation.
     */
    public static void finishWithReceive() {
        if (sHandler != null) {
            sHandler.sendEmptyMessage(MSG_RESULT_RECEIVE);
        }
    }

    /**
     * Creates and sets the screenshot to be animated.
     * Must be called on the UI thread.
     * @param screenshot to be animated
     */
    public static void makeScreenshot(Context context) {
        sScreenBitmap = createScreenshot(context);
    }

    public static void setCallback(P2pEventListener.Callback callback) {
        sCallback = callback;
    }

    private void onStartAnimationUpdate(ValueAnimator animation) {
        // Just scale the screenshot down
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE + (1f - scale) *
                (1 - INITIAL_SCREENSHOT_SCALE);

        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);
    }

    private void onSuccessCloneAnimationUpdate(ValueAnimator animation) {
        // Clone the screenshot
        if (mClonedView.getVisibility() != View.VISIBLE) {
            // Scale clone to same size
            mClonedView.setScaleX(mScreenshotView.getScaleX());
            mClonedView.setScaleY(mScreenshotView.getScaleY());
            mClonedView.setVisibility(View.VISIBLE);

            mScreenshotView.setAlpha(0.5f);
        }

        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE - (scale *
                (INITIAL_SCREENSHOT_SCALE - FINAL_SCREENSHOT_SCALE));

        mClonedView.setScaleX(scaleT);
        mClonedView.setScaleY(scaleT);
    }

    private void onSuccessUpUpdate(ValueAnimator animation) {
        // Scale the screenshot all the way back to the front,
        // scale the clone down to zero.
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE +
                (scale * (1.0f - INITIAL_SCREENSHOT_SCALE));
        float alpha = 0.5f + (0.5f * mAlphaDownInterpolator.getInterpolation(t));
        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);
        mScreenshotView.setAlpha(alpha);
    }

    private void onFailureUpdate(ValueAnimator animation) {
        // Scale back from initial scale to normal scale
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE + (scale *
                (1.0f - INITIAL_SCREENSHOT_SCALE));

        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);

    }

    private void onReceiveUpdate(ValueAnimator animation) {
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float offset = mScaleDownInterpolator.getInterpolation(t);

        mScreenshotView.setX(offset * mScreenshotWidth);
        mShareText.setX(offset * mScreenshotWidth);
    }
    @Override
    public void onAnimationUpdate(ValueAnimator animation) {
        if (animation == mStartAnimator) {
            onStartAnimationUpdate(animation);
        } else if (animation == mScaleDownAnimator) {
            onSuccessCloneAnimationUpdate(animation);
        } else if (animation == mScaleUpAnimator) {
            onSuccessUpUpdate(animation);
        } else if (animation == mFailureAnimator) {
            onFailureUpdate(animation);
        } else if (animation == mFailureAnimator) {
            onFailureUpdate(animation);
        } else if (animation == mReceiveAnimator) {
            onReceiveUpdate(animation);
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        mWaitingForResult = false;
        switch (msg.what) {
            case MSG_RESULT_FAILURE: {
                mResult = RESULT_FAILURE;
                break;
            }
            case MSG_RESULT_SEND: {
                mResult = RESULT_SEND;
                break;
            }
            case MSG_RESULT_RECEIVE: {
                mResult = RESULT_RECV;
                break;
            }
        }

        if (mStartAnimDone) {
            playEndAnimation(mResult);
        }
        return true;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (sCallback != null) {
            sCallback.onP2pSendConfirmed();
        }
        return true;
    }

    /**
     * @return the current display rotation in degrees
     */
    static float getDegreesForRotation(int value) {
        switch (value) {
        case Surface.ROTATION_90:
            return 90f;
        case Surface.ROTATION_180:
            return 180f;
        case Surface.ROTATION_270:
            return 270f;
        }
        return 0f;
    }

    /**
     * Returns a screenshot of the current display contents.
     * @param context Context.
     * @return
     */
    static Bitmap createScreenshot(Context context) {
        synchronized(P2pAnimationActivity.class) {
            if (mDisplay == null) {
                // First time, init statics
                mDisplayMetrics = new DisplayMetrics();
                mDisplayMatrix = new Matrix();
                mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                mDisplay = mWindowManager.getDefaultDisplay();
            }
        }
        // We need to orient the screenshot correctly (and the Surface api seems to
        // take screenshots only in the natural orientation of the device :!)
        mDisplay.getRealMetrics(mDisplayMetrics);
        float[] dims = {mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels};
        float degrees = getDegreesForRotation(mDisplay.getRotation());
        boolean requiresRotation = (degrees > 0);
        if (requiresRotation) {
            // Get the dimensions of the device in its native orientation
            mDisplayMatrix.reset();
            mDisplayMatrix.preRotate(-degrees);
            mDisplayMatrix.mapPoints(dims);
            dims[0] = Math.abs(dims[0]);
            dims[1] = Math.abs(dims[1]);
        }

        Bitmap bitmap = Surface.screenshot((int) dims[0], (int) dims[1]);
        // Bail if we couldn't take the screenshot
        if (bitmap == null) {
            return null;
        }

        if (requiresRotation) {
            // Rotate the screenshot to the current orientation
            Bitmap ss = Bitmap.createBitmap(mDisplayMetrics.widthPixels,
                    mDisplayMetrics.heightPixels, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(ss);
            c.translate(ss.getWidth() / 2, ss.getHeight() / 2);
            c.rotate(360f - degrees);
            c.translate(-dims[0] / 2, -dims[1] / 2);
            c.drawBitmap(bitmap, 0, 0, null);

            bitmap = ss;
        }

        return bitmap;
    }

}