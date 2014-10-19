/*
 * Copyright (C) 2014 Kevin Mark
 *
 * This file is part of XHangouts.
 *
 * XHangouts is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * XHangouts is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with XHangouts.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.versobit.kmark.xhangouts;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class XHangouts implements IXposedHookLoadPackage {

    private static final String TAG = "XHangouts";

    private static final String ACTIVITY_THREAD_CLASS = "android.app.ActivityThread";
    private static final String ACTIVITY_THREAD_CURRENTACTHREAD = "currentActivityThread";
    private static final String ACTIVITY_THREAD_GETSYSCTX = "getSystemContext";

    static final String HANGOUTS_PKG_NAME = "com.google.android.talk";

    private static final String HANGOUTS_ESAPP_CLASS = "com.google.android.apps.hangouts.phone.EsApplication";
    private static final String HANGOUTS_ESAPP_ONCREATE = "onCreate";

    private static final String HANGOUTS_PROCESS_MMS_IMG_CLASS = "bvp";
    // private static a(IIIILandroid/net/Uri;)[B
    private static final String HANGOUTS_PROCESS_MMS_IMG_METHOD = "a";

    private static final String HANGOUTS_ESPROVIDER_CLASS = "com.google.android.apps.hangouts.content.EsProvider";
    // private static d(Ljava/lang/String;)Ljava/lang/String
    private static final String HANGOUTS_ESPROVIDER_GET_SCRATCH_FILE = "d";

    private static final String HANGOUTS_VIEWS_COMPOSEMSGVIEW = "com.google.android.apps.hangouts.views.ComposeMessageView";
    private static final String HANGOUTS_VIEWS_COMPOSEMSGVIEW_EDITTEXT = "i";
    // public onEditorAction(Landroid/widget/TextView;ILandroid/view/KeyEvent;)Z
    private static final String HANGOUTS_VIEWS_COMEPOSEMSGVIEW_ONEDITORACTION = "onEditorAction";

    private static final String TESTED_VERSION_STR = "2.3.75731955";
    private static final int TESTED_VERSION_INT = 22037769;

    // Not certain if I need a WeakReference here. Without it could prevent the Context from being closed?
    private WeakReference<Context> hangoutsCtx;

    private static final class Config {

        private static final Uri ALL_PREFS_URI = Uri.parse("content://" + SettingsProvider.AUTHORITY + "/all");

        // Give us some sane defaults, just in case
        private static boolean modEnabled = true;
        private static boolean resizing = true;
        private static boolean rotation = true;
        private static int rotateMode = -1;
        private static int imageWidth = 640;
        private static int imageHeight = 640;
        private static Setting.ImageFormat imageFormat = Setting.ImageFormat.JPEG;
        private static int imageQuality = 60;
        private static int enterKey = Setting.UiEnterKey.EMOJI_SELECTOR.toInt();
        private static boolean debug = false;

        private static void reload(Context ctx) {
            Cursor prefs = ctx.getContentResolver().query(ALL_PREFS_URI, null, null, null, null);
            if(prefs == null) {
                log("Failed to retrieve settings!");
                return;
            }
            while(prefs.moveToNext()) {
                switch (Setting.fromString(prefs.getString(SettingsProvider.QUERY_ALL_KEY))) {
                    case MOD_ENABLED:
                        modEnabled = prefs.getInt(SettingsProvider.QUERY_ALL_VALUE) == SettingsProvider.TRUE;
                        continue;
                    case MMS_RESIZE_ENABLED:
                        resizing = prefs.getInt(SettingsProvider.QUERY_ALL_VALUE) == SettingsProvider.TRUE;
                        continue;
                    case MMS_ROTATE_ENABLED:
                        rotation = prefs.getInt(SettingsProvider.QUERY_ALL_VALUE) == SettingsProvider.TRUE;
                        continue;
                    case MMS_ROTATE_MODE:
                        rotateMode = prefs.getInt(SettingsProvider.QUERY_ALL_VALUE);
                        continue;
                    case MMS_SCALE_WIDTH:
                        imageWidth = prefs.getInt(SettingsProvider.QUERY_ALL_VALUE);
                        continue;
                    case MMS_SCALE_HEIGHT:
                        imageHeight = prefs.getInt(SettingsProvider.QUERY_ALL_VALUE);
                        continue;
                    case MMS_IMAGE_TYPE:
                        imageFormat = Setting.ImageFormat.fromInt(prefs.getInt(SettingsProvider.QUERY_ALL_VALUE));
                        continue;
                    case MMS_IMAGE_QUALITY:
                        imageQuality = prefs.getInt(SettingsProvider.QUERY_ALL_VALUE);
                        continue;
                    case UI_ENTER_KEY:
                        enterKey = prefs.getInt(SettingsProvider.QUERY_ALL_VALUE);
                    case DEBUG:
                        debug = prefs.getInt(SettingsProvider.QUERY_ALL_VALUE) == SettingsProvider.TRUE;
                }
            }
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if(loadPackageParam.packageName.equals(BuildConfig.PACKAGE_NAME)) {
            XposedHelpers.findAndHookMethod(XApp.class.getCanonicalName(), loadPackageParam.classLoader, "isActive", XC_MethodReplacement.returnConstant(true));
        }
        if(!loadPackageParam.packageName.equals(HANGOUTS_PKG_NAME)) {
            return;
        }

        Object activityThread = XposedHelpers.callStaticMethod(XposedHelpers.findClass(ACTIVITY_THREAD_CLASS, null), ACTIVITY_THREAD_CURRENTACTHREAD);
        final Context systemCtx = (Context)XposedHelpers.callMethod(activityThread, ACTIVITY_THREAD_GETSYSCTX);

        Config.reload(systemCtx);
        if(!Config.modEnabled) {
            return;
        }

        debug("--- LOADING XHANGOUTS ---", false);
        debug(String.format("XHangouts v%s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), false);

        PackageInfo pi = systemCtx.getPackageManager().getPackageInfo(HANGOUTS_PKG_NAME, 0);
        debug(String.format("Google Hangouts v%s (%d)", pi.versionName, pi.versionCode), false);
        // TODO: replace this with something more robust?
        if(pi.versionCode != TESTED_VERSION_INT) {
            log(String.format("Warning: Your Hangouts version differs from the version XHangouts was built against: v%s (%d)", TESTED_VERSION_STR, TESTED_VERSION_INT));
        }

        // Get application context to use later
        XposedHelpers.findAndHookMethod(HANGOUTS_ESAPP_CLASS, loadPackageParam.classLoader, HANGOUTS_ESAPP_ONCREATE, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                debug("Context set.");
                hangoutsCtx = new WeakReference<Context>((Context)param.thisObject);
            }
        });


        // This is called when the user hits the send button on an image MMS
        // TODO: there seem to be a few instances where this is not called, find alternate code paths
        XposedHelpers.findAndHookMethod(HANGOUTS_PROCESS_MMS_IMG_CLASS, loadPackageParam.classLoader, HANGOUTS_PROCESS_MMS_IMG_METHOD, int.class, int.class, int.class, int.class, Uri.class, new XC_MethodHook() {
            // int1 = ? (usually zero, it seems)
            // int2 = max scaled width, appears to be 640 if landscape or square, 480 if portrait
            // int3 = max scaled height, appears to be 640 if portrait, 480 if landscape or square
            // int4 ?, seems to be width * height - 1024 = 306176
            // Uri1 content:// path that references the input image

            // At least one instance has been reported of int2, int3, and int4 being populated with
            // much larger values resulting in an image much too large to be sent via MMS

            // We're not replacing the method so that even if we fail, which is conceivable, we
            // safely fall back to the original Hangouts result. This also means that if the Hangout
            // function call does something weird that needs to be done (that we don't do) it still
            // gets done. Downside is that we're running code that may never be used.

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Config.reload(systemCtx);
                if(!Config.modEnabled || !Config.resizing) {
                    return;
                }

                // Thanks to cottonBallPaws @ http://stackoverflow.com/a/4250279/238374

                final int paramWidth = (Integer)param.args[1];
                final int paramHeight = (Integer)param.args[2];
                final Uri imgUri = (Uri)param.args[4];

                // Prevents leak of Hangouts account email to the debug log
                final String safeUri = imgUri.toString().substring(0, imgUri.toString().indexOf("?"));

                debug(String.format("New MMS image! %d, %d, %s, %s, %s", paramWidth, paramHeight, safeUri, param.args[0], param.args[3]));
                String quality = Config.imageFormat == Setting.ImageFormat.PNG ? "lossless" : String.valueOf(Config.imageQuality);
                debug(String.format("Configuration: %d×%d, %s at %s quality", Config.imageWidth, Config.imageHeight,
                        Config.imageFormat.toString(), quality));

                ContentResolver esAppResolver = hangoutsCtx.get().getContentResolver();
                InputStream imgStream = esAppResolver.openInputStream(imgUri);

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(imgStream, null, options);
                imgStream.close();

                int srcW = options.outWidth;
                int srcH = options.outHeight;

                debug(String.format("Original: %d×%d", srcW, srcH));

                int rotation = 0;
                if(Config.rotation) {
                    rotation = Config.rotateMode;
                    if(rotation == -1) {
                        // Find the rotated "real" dimensions to determine proper final scaling
                        // ExifInterface requires a real file path so we ask Hangouts to tell us where the cached file is located
                        String scratchId = imgUri.getPathSegments().get(1);
                        String filePath = (String) XposedHelpers.callStaticMethod(XposedHelpers.findClass(HANGOUTS_ESPROVIDER_CLASS, loadPackageParam.classLoader), HANGOUTS_ESPROVIDER_GET_SCRATCH_FILE, scratchId);
                        debug(String.format("Cache file located: %s", filePath));
                        ExifInterface exif = new ExifInterface(filePath);
                        // Let's pretend other orientation modes don't exist
                        switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            case ExifInterface.ORIENTATION_ROTATE_90:
                                rotation = 90;
                                break;
                            case ExifInterface.ORIENTATION_ROTATE_180:
                                rotation = 180;
                                break;
                            case ExifInterface.ORIENTATION_ROTATE_270:
                                rotation = 270;
                                break;
                            default:
                                rotation = 0;
                        }
                    }
                    if (rotation != 0) {
                        // Technically we could just swap width and height if rotation = 90 or 270 but
                        // this is a much more fun reference implementation.
                        // TODO: apply rotation to max values as well? Rotated images are scaled more than non
                        Matrix imgMatrix = new Matrix();
                        imgMatrix.postRotate(rotation);
                        RectF imgRect = new RectF();
                        imgMatrix.mapRect(imgRect, new RectF(0, 0, srcW, srcH));
                        srcW = Math.round(imgRect.width());
                        srcH = Math.round(imgRect.height());
                        debug(String.format("Rotated: %d×%d, Rotation: %d°", srcW, srcH, rotation));
                    }
                }

                // Find the highest possible sample size divisor that is still larger than our maxes
                int inSS = 1;
                while((srcW / 2 > Config.imageWidth) || (srcH / 2 > Config.imageHeight)) {
                    srcW /= 2;
                    srcH /= 2;
                    inSS *= 2;
                }

                // Use the longest side to determine scale, this should always be <= 1
                float scale = ((float)(srcW > srcH ? Config.imageWidth : Config.imageHeight)) / (srcW > srcH ? srcW : srcH);

                debug(String.format("Estimated: %d×%d, Sample Size: 1/%d, Scale: %f", srcW, srcH, inSS, scale));

                // Load the sampled image into memory
                options.inJustDecodeBounds = false;
                options.inDither = false;
                options.inSampleSize = inSS;
                options.inScaled = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                imgStream = esAppResolver.openInputStream(imgUri);
                Bitmap sampled = BitmapFactory.decodeStream(imgStream, null, options);
                imgStream.close();
                debug(String.format("Sampled: %d×%d", sampled.getWidth(), sampled.getHeight()));

                // Load our scale and rotation changes into a matrix and use it to create the final bitmap
                Matrix m = new Matrix();
                m.postScale(scale, scale);
                m.postRotate(rotation);
                Bitmap scaled = Bitmap.createBitmap(sampled, 0, 0, sampled.getWidth(), sampled.getHeight(), m, true);
                sampled.recycle();
                debug(String.format("Scaled: %d×%d", scaled.getWidth(), scaled.getHeight()));

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                Bitmap.CompressFormat compressFormat = null;
                final int compressQ = Config.imageFormat == Setting.ImageFormat.PNG ? 0 : Config.imageQuality;
                switch (Config.imageFormat) {
                    case PNG:
                        compressFormat = Bitmap.CompressFormat.PNG;
                        break;
                    case JPEG:
                        compressFormat = Bitmap.CompressFormat.JPEG;
                        break;
                }
                scaled.compress(compressFormat, compressQ, output);
                final int bytes = output.size();
                scaled.recycle();

                param.setResult(output.toByteArray());
                output.close();
                debug(String.format("MMS image processing complete. %d bytes", bytes));
            }
        });

        Class<?> ComposeMessageView = XposedHelpers.findClass(HANGOUTS_VIEWS_COMPOSEMSGVIEW, loadPackageParam.classLoader);
        XposedHelpers.findAndHookConstructor(ComposeMessageView, Context.class, AttributeSet.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Config.reload((Context)param.args[0]);
                if(Config.modEnabled) {
                    Setting.UiEnterKey enterKey = Setting.UiEnterKey.fromInt(Config.enterKey);
                    debug(String.format("ComposeMessageView: %s", enterKey.name()));
                    if(enterKey != Setting.UiEnterKey.EMOJI_SELECTOR) {
                        EditText et = (EditText)XposedHelpers.getObjectField(param.thisObject, HANGOUTS_VIEWS_COMPOSEMSGVIEW_EDITTEXT);
                        // Remove Emoji selector (works for new line)
                        int inputType = et.getInputType() ^ InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE;
                        if(enterKey == Setting.UiEnterKey.SEND) {
                            // Disable multi-line input which shows the send button
                            inputType ^= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
                        }
                        et.setInputType(inputType);
                    }
                }
            }
        });

        // Called by at least SwiftKey and Fleksy on new line, but not the AOSP or Google keyboard
        XposedHelpers.findAndHookMethod(ComposeMessageView, HANGOUTS_VIEWS_COMEPOSEMSGVIEW_ONEDITORACTION, TextView.class, int.class, KeyEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int actionId = (Integer)param.args[1];
                if(Config.modEnabled && actionId == EditorInfo.IME_NULL && Config.enterKey == Setting.UiEnterKey.NEWLINE.toInt()) {
                    param.setResult(false); // We do not handle the enter action, and it adds a newline for us
                }
            }
        });

        debug("--- LOAD COMPLETE ---", false);
    }

    private static void debug(String msg) {
        debug(msg, true);
    }

    private static void debug(String msg, boolean tag) {
        if(Config.debug) {
            log(msg, tag);
        }
    }

    private static void log(String msg) {
        log(msg, true);
    }

    private static void log(String msg, boolean tag) {
        XposedBridge.log((tag ? TAG + ": " : "") + msg);
    }
}
