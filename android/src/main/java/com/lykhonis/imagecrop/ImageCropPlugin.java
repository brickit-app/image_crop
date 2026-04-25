package com.lykhonis.imagecrop;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;

public final class ImageCropPlugin implements FlutterPlugin , ActivityAware, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener {
    private static final int PERMISSION_REQUEST_CODE = 13094;

    private MethodChannel channel;

    private ActivityPluginBinding binding;
    private Activity activity;
    private Context applicationContext;
    private Result permissionRequestResult;
    private ExecutorService executor;

    public ImageCropPlugin() {}

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        applicationContext = binding.getApplicationContext();
        this.setup(binding.getBinaryMessenger());
    }
  
    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
        synchronized (this) {
            if (executor != null) {
                executor.shutdown();
                executor = null;
            }
        }
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding activityPluginBinding) {
        binding = activityPluginBinding;
        activity = activityPluginBinding.getActivity();
        activityPluginBinding.addRequestPermissionsResultListener(this);
    }
   
    @Override
    public void onDetachedFromActivity() {
        activity = null;
        if(binding != null){
            binding.removeRequestPermissionsResultListener(this);
        }
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding activityPluginBinding) {
        this.onAttachedToActivity(activityPluginBinding);
    }
  
    @Override
    public void onDetachedFromActivityForConfigChanges() {
        this.onDetachedFromActivity();
    }
  
    private void setup(BinaryMessenger messenger) {
        channel = new MethodChannel(messenger, "plugins.lykhonis.com/image_crop");
        channel.setMethodCallHandler(this);
    }


    @SuppressWarnings("ConstantConditions")
    @Override
    public void onMethodCall(MethodCall call, Result result) {
        if ("cropImage".equals(call.method)) {
            String path = call.argument("path");
            String safePath = validateAndCanonicalizeReadablePath(path);
            if (safePath == null) {
                result.error("INVALID_PATH", "File path must resolve under app-accessible storage", null);
                return;
            }
            double scale = call.argument("scale");
            double left = call.argument("left");
            double top = call.argument("top");
            double right = call.argument("right");
            double bottom = call.argument("bottom");
            RectF area = new RectF((float) left, (float) top, (float) right, (float) bottom);
            cropImage(safePath, area, (float) scale, result);
        } else if ("sampleImage".equals(call.method)) {
            String path = call.argument("path");
            String safePath = validateAndCanonicalizeReadablePath(path);
            if (safePath == null) {
                result.error("INVALID_PATH", "File path must resolve under app-accessible storage", null);
                return;
            }
            int maximumWidth = call.argument("maximumWidth");
            int maximumHeight = call.argument("maximumHeight");
            sampleImage(safePath, maximumWidth, maximumHeight, result);
        } else if ("getImageOptions".equals(call.method)) {
            String path = call.argument("path");
            String safePath = validateAndCanonicalizeReadablePath(path);
            if (safePath == null) {
                result.error("INVALID_PATH", "File path must resolve under app-accessible storage", null);
                return;
            }
            getImageOptions(safePath, result);
        } else if ("requestPermissions".equals(call.method)) {
            requestPermissions(result);
        } else {
            result.notImplemented();
        }
    }

    private synchronized void io(@NonNull Runnable runnable) {
        if (executor == null) {
            executor = Executors.newCachedThreadPool();
        }
        executor.execute(runnable);
    }

    private void ui(@NonNull Runnable runnable) {
        Activity a = activity;
        if (a != null) {
            a.runOnUiThread(runnable);
        } else {
            new Handler(Looper.getMainLooper()).post(runnable);
        }
    }

    private void cropImage(final String path, final RectF area, final float scale, final Result result) {
        io(new Runnable() {
            @Override
            public void run() {
                File srcFile = new File(path);
                if (!srcFile.exists()) {
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            result.error("INVALID", "Image source cannot be opened", null);
                        }
                    });
                    return;
                }

                ImageOptions options = decodeImageOptions(path);
                int deg = ((options.getDegrees() % 360) + 360) % 360;
                if (deg != 0 && deg != 90 && deg != 180 && deg != 270) {
                    cropImageLegacyFullDecode(path, area, scale, result, options);
                    return;
                }

                BitmapRegionDecoder decoder = null;
                Bitmap partial = null;
                Bitmap dstBitmap = null;
                Canvas canvas = null;
                try {
                    decoder = BitmapRegionDecoder.newInstance(path, false);
                    if (decoder == null) {
                        cropImageLegacyFullDecode(path, area, scale, result, options);
                        return;
                    }

                    int sw = decoder.getWidth();
                    int sh = decoder.getHeight();
                    int lw = options.getWidth();
                    int lh = options.getHeight();

                    int li = (int) (lw * area.left);
                    int ti = (int) (lh * area.top);
                    int ri = (int) (lw * area.right);
                    int bi = (int) (lh * area.bottom);
                    li = clamp(li, 0, Math.max(0, lw - 1));
                    ti = clamp(ti, 0, Math.max(0, lh - 1));
                    ri = clamp(ri, li + 1, lw);
                    bi = clamp(bi, ti + 1, lh);

                    int outW = Math.max(1, (int) (lw * area.width() * scale));
                    int outH = Math.max(1, (int) (lh * area.height() * scale));

                    Rect storageRegion = logicalCropToStorageRegion(li, ti, ri, bi, deg, sw, sh);
                    if (storageRegion.width() <= 0 || storageRegion.height() <= 0) {
                        cropImageLegacyFullDecode(path, area, scale, result, options);
                        return;
                    }

                    BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                    decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    decodeOpts.inSampleSize = calculateInSampleSize(
                            storageRegion.width(),
                            storageRegion.height(),
                            outW,
                            outH);

                    partial = decoder.decodeRegion(storageRegion, decodeOpts);
                    if (partial == null) {
                        cropImageLegacyFullDecode(path, area, scale, result, options);
                        return;
                    }

                    dstBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                    canvas = new Canvas(dstBitmap);
                    Paint paint = new Paint();
                    paint.setAntiAlias(true);
                    paint.setFilterBitmap(true);
                    paint.setDither(true);

                    int ins = decodeOpts.inSampleSize;
                    drawCroppedRegion(partial, storageRegion, ins, deg, sw, sh, li, ti, ri, bi, outW, outH, canvas, paint);

                    final File dstFile = createTemporaryImageFile();
                    compressBitmap(dstBitmap, dstFile);
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            result.success(dstFile.getAbsolutePath());
                        }
                    });
                } catch (IOException e) {
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            result.error("INVALID", "Image could not be saved", e);
                        }
                    });
                } catch (IllegalArgumentException e) {
                    Log.w("ImageCrop", "Region decode failed, falling back", e);
                    cropImageLegacyFullDecode(path, area, scale, result, options);
                    return;
                } finally {
                    if (canvas != null) {
                        canvas.setBitmap(null);
                    }
                    if (partial != null) {
                        partial.recycle();
                    }
                    if (dstBitmap != null) {
                        dstBitmap.recycle();
                    }
                    if (decoder != null) {
                        decoder.recycle();
                    }
                }
            }
        });
    }

    /**
     * Maps logical upright crop [li,ri) x [ti,bi) to a storage-space rectangle (half-open right/bottom
     * for {@link BitmapRegionDecoder#decodeRegion}) matching EXIF rotation used by {@link ImageOptions}.
     */
    private static Rect logicalCropToStorageRegion(int li, int ti, int ri, int bi, int deg, int sw, int sh) {
        PointF p = new PointF();
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float[][] corners = {
                {li, ti}, {ri, ti},
                {ri, bi}, {li, bi},
        };
        for (float[] c : corners) {
            logicalPointToStorage(c[0], c[1], deg, sw, sh, p);
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        int left = clamp((int) Math.floor(minX), 0, sw - 1);
        int top = clamp((int) Math.floor(minY), 0, sh - 1);
        int right = clamp((int) Math.ceil(maxX), left + 1, sw);
        int bottom = clamp((int) Math.ceil(maxY), top + 1, sh);
        return new Rect(left, top, right, bottom);
    }

    /**
     * Maps upright logical pixel (xL, yL) to storage (file) pixel coordinates.
     * Aligns with {@code Bitmap.createBitmap} + {@code Matrix.postRotate(degrees)} used in the legacy path
     * for standard EXIF rotations (0, 90, 180, 270).
     */
    private static void logicalPointToStorage(float xL, float yL, int deg, int sw, int sh, PointF out) {
        switch (deg) {
            case 0:
                out.set(xL, yL);
                break;
            case 90:
                out.set(sw - 1f - yL, xL);
                break;
            case 180:
                out.set(sw - 1f - xL, sh - 1f - yL);
                break;
            case 270:
                out.set(sh - 1f - yL, xL);
                break;
            default:
                out.set(xL, yL);
                break;
        }
    }

    private static void drawCroppedRegion(
            Bitmap partial,
            Rect storageRegion,
            int inSampleSize,
            int deg,
            int sw,
            int sh,
            int li,
            int ti,
            int ri,
            int bi,
            int outW,
            int outH,
            Canvas canvas,
            Paint paint) {
        int pw = partial.getWidth();
        int ph = partial.getHeight();
        if (deg == 0) {
            Rect src = new Rect(0, 0, pw, ph);
            Rect dst = new Rect(0, 0, outW, outH);
            canvas.drawBitmap(partial, src, dst, paint);
            return;
        }

        float ins = Math.max(1, inSampleSize);
        float sl = storageRegion.left;
        float st = storageRegion.top;

        PointF s = new PointF();
        float[] srcPts = new float[6];
        float[] dstPts = new float[6];
        int k = 0;
        float[][] logicalCorners = {
                {li, ti}, {ri, ti}, {ri, bi},
        };
        float[][] dstCornerPts = {
                {0, 0}, {outW, 0}, {outW, outH},
        };
        for (int i = 0; i < 3; i++) {
            logicalPointToStorage(logicalCorners[i][0], logicalCorners[i][1], deg, sw, sh, s);
            srcPts[k] = (s.x - sl) / ins;
            srcPts[k + 1] = (s.y - st) / ins;
            dstPts[k] = dstCornerPts[i][0];
            dstPts[k + 1] = dstCornerPts[i][1];
            k += 2;
        }

        Matrix m = new Matrix();
        if (!m.setPolyToPoly(srcPts, 0, dstPts, 0, 3)) {
            throw new IllegalArgumentException("setPolyToPoly failed");
        }
        canvas.drawBitmap(partial, m, paint);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Full-image decode path for unsupported formats or when region decode fails. */
    private void cropImageLegacyFullDecode(
            final String path,
            final RectF area,
            final float scale,
            final Result result,
            final ImageOptions options) {
        Bitmap srcBitmap = BitmapFactory.decodeFile(path, null);
        if (srcBitmap == null) {
            ui(new Runnable() {
                @Override
                public void run() {
                    result.error("INVALID", "Image source cannot be decoded", null);
                }
            });
            return;
        }
        try {
            if (options.isFlippedDimensions()) {
                Matrix transformations = new Matrix();
                transformations.postRotate(options.getDegrees());
                Bitmap oldBitmap = srcBitmap;
                srcBitmap = Bitmap.createBitmap(oldBitmap,
                        0, 0,
                        oldBitmap.getWidth(), oldBitmap.getHeight(),
                        transformations, true);
                oldBitmap.recycle();
            }

            int width = Math.max(1, (int) (options.getWidth() * area.width() * scale));
            int height = Math.max(1, (int) (options.getHeight() * area.height() * scale));

            Bitmap dstBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(dstBitmap);

            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setFilterBitmap(true);
            paint.setDither(true);

            Rect srcRect = new Rect((int) (srcBitmap.getWidth() * area.left),
                    (int) (srcBitmap.getHeight() * area.top),
                    (int) (srcBitmap.getWidth() * area.right),
                    (int) (srcBitmap.getHeight() * area.bottom));
            Rect dstRect = new Rect(0, 0, width, height);
            canvas.drawBitmap(srcBitmap, srcRect, dstRect, paint);

            final File dstFile = createTemporaryImageFile();
            compressBitmap(dstBitmap, dstFile);
            ui(new Runnable() {
                @Override
                public void run() {
                    result.success(dstFile.getAbsolutePath());
                }
            });
            canvas.setBitmap(null);
            dstBitmap.recycle();
        } catch (final IOException e) {
            ui(new Runnable() {
                @Override
                public void run() {
                    result.error("INVALID", "Image could not be saved", e);
                }
            });
        } finally {
            srcBitmap.recycle();
        }
    }

    private void sampleImage(final String path, final int maximumWidth, final int maximumHeight, final Result result) {
        io(new Runnable() {
            @Override
            public void run() {
                File srcFile = new File(path);
                if (!srcFile.exists()) {
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            result.error("INVALID", "Image source cannot be opened", null);
                        }
                    });
                    return;
                }

                ImageOptions options = decodeImageOptions(path);
                BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
                bitmapOptions.inSampleSize = calculateInSampleSize(options.getWidth(), options.getHeight(),
                                                                   maximumWidth, maximumHeight);

                Bitmap bitmap = BitmapFactory.decodeFile(path, bitmapOptions);
                if (bitmap == null) {
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            result.error("INVALID", "Image source cannot be decoded", null);
                        }
                    });
                    return;
                }

                if (bitmap.getWidth() > maximumWidth || bitmap.getHeight() > maximumHeight) {
                    float rw = maximumWidth / (float) bitmap.getWidth();
                    float rh = maximumHeight / (float) bitmap.getHeight();
                    float ratio = Math.min(rw, rh);
                    Bitmap sample = bitmap;
                    bitmap = Bitmap.createScaledBitmap(
                            sample,
                            Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                            Math.max(1, Math.round(bitmap.getHeight() * ratio)),
                            true);
                    sample.recycle();
                }

                try {
                    final File dstFile = createTemporaryImageFile();
                    compressBitmap(bitmap, dstFile);
                    copyExif(srcFile, dstFile);
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            result.success(dstFile.getAbsolutePath());
                        }
                    });
                } catch (final IOException e) {
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            result.error("INVALID", "Image could not be saved", e);
                        }
                    });
                } finally {
                    bitmap.recycle();
                }
            }
        });
    }

    @SuppressWarnings("TryFinallyCanBeTryWithResources")
    private void compressBitmap(Bitmap bitmap, File file) throws IOException {
        OutputStream outputStream = new FileOutputStream(file);
        try {
            boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
            if (!compressed) {
                throw new IOException("Failed to compress bitmap into JPEG");
            }
        } finally {
            try {
                outputStream.close();
            } catch (IOException ignore) {
            }
        }
    }

    private int calculateInSampleSize(int width, int height, int maximumWidth, int maximumHeight) {
        int inSampleSize = 1;

        if (height > maximumHeight || width > maximumWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= maximumHeight && (halfWidth / inSampleSize) >= maximumWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private void getImageOptions(final String path, final Result result) {
        io(new Runnable() {
            @Override
            public void run() {
                File file = new File(path);
                if (!file.exists()) {
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            result.error("INVALID", "Image source cannot be opened", null);
                        }
                    });
                    return;
                }

                ImageOptions options = decodeImageOptions(path);
                final Map<String, Object> properties = new HashMap<>();
                properties.put("width", options.getWidth());
                properties.put("height", options.getHeight());

                ui(new Runnable() {
                    @Override
                    public void run() {
                        result.success(properties);
                    }
                });
            }
        });
    }

    private void requestPermissions(Result result) {
        Activity a = activity;
        if (a == null) {
            result.error("NO_ACTIVITY", "Cannot request permissions without an activity", null);
            return;
        }
        // Plugin only reads paths provided by the app and writes to app-private cache (no broad storage write).
        // API 33+: legacy READ_EXTERNAL_STORAGE does not apply; photo access uses picker / Photo APIs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.success(true);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (a.checkSelfPermission(READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                result.success(true);
            } else {
                permissionRequestResult = result;
                a.requestPermissions(new String[]{READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            }
        } else {
            result.success(true);
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE && permissionRequestResult != null) {
            int readExternalStorage = getPermissionGrantResult(READ_EXTERNAL_STORAGE, permissions, grantResults);
            permissionRequestResult.success(readExternalStorage == PackageManager.PERMISSION_GRANTED);
            permissionRequestResult = null;
        }
        return false;
    }

    /**
     * Matches a permission name to its grant result. Must iterate {@code permissions} array length,
     * never {@code permission.length()} (string length).
     */
    private int getPermissionGrantResult(String permission, String[] permissions, int[] grantResults) {
        if (permissions == null || grantResults == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        int n = Math.min(permissions.length, grantResults.length);
        for (int i = 0; i < n; i++) {
            if (permission.equals(permissions[i])) {
                return grantResults[i];
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    /**
     * Resolves {@code path} canonically and ensures it lies under app-private dirs (mitigates path traversal).
     *
     * @return canonical absolute path, or {@code null} if invalid or outside allowed roots
     */
    private String validateAndCanonicalizeReadablePath(String path) {
        Context ctx = activity != null ? activity : applicationContext;
        if (ctx == null || path == null || path.isEmpty()) {
            return null;
        }
        if (path.indexOf('\0') >= 0) {
            return null;
        }
        try {
            File canonical = new File(path).getCanonicalFile();
            String cp = canonical.getPath();
            List<String> roots = allowedReadablePathRoots(ctx);
            for (String root : roots) {
                if (cp.equals(root) || cp.startsWith(root + File.separator)) {
                    return cp;
                }
            }
        } catch (IOException e) {
            Log.w("ImageCrop", "Path validation failed", e);
        }
        return null;
    }

    private static List<String> allowedReadablePathRoots(Context ctx) {
        List<String> out = new ArrayList<>();
        addCanonicalRoot(out, ctx.getCacheDir());
        addCanonicalRoot(out, ctx.getFilesDir());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            addCanonicalRoot(out, ctx.getCodeCacheDir());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            addCanonicalRoot(out, ctx.getDataDir());
        }
        addCanonicalRoot(out, ctx.getExternalCacheDir());
        addCanonicalRoot(out, ctx.getExternalFilesDir(null));
        File[] extFiles = ctx.getExternalFilesDirs(null);
        if (extFiles != null) {
            for (File d : extFiles) {
                addCanonicalRoot(out, d);
            }
        }
        File[] extCaches = ctx.getExternalCacheDirs();
        if (extCaches != null) {
            for (File d : extCaches) {
                addCanonicalRoot(out, d);
            }
        }
        return out;
    }

    private static void addCanonicalRoot(List<String> out, File dir) {
        if (dir == null) {
            return;
        }
        try {
            String p = dir.getCanonicalPath();
            for (String existing : out) {
                if (p.equals(existing)) {
                    return;
                }
            }
            out.add(p);
        } catch (IOException e) {
            Log.w("ImageCrop", "Could not canonicalize directory", e);
        }
    }

    private File createTemporaryImageFile() throws IOException {
        Context ctx = activity != null ? activity : applicationContext;
        if (ctx == null) {
            throw new IOException("No context available for temporary file");
        }
        File directory = ctx.getCacheDir();
        String name = "image_crop_" + UUID.randomUUID().toString();
        return File.createTempFile(name, ".jpg", directory);
    }

    private ImageOptions decodeImageOptions(String path) {
        int rotationDegrees = 0;
        try {
            ExifInterface exif = new ExifInterface(path);
            rotationDegrees = exif.getRotationDegrees();
        } catch (IOException e) {
            Log.e("ImageCrop", "Failed to read a file " + path, e);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        return new ImageOptions(options.outWidth, options.outHeight, rotationDegrees);
    }

    private void copyExif(File source, File destination) {
        try {
            ExifInterface sourceExif = new ExifInterface(source.getAbsolutePath());
            ExifInterface destinationExif = new ExifInterface(destination.getAbsolutePath());

            List<String> tags =
                    Arrays.asList(
                            ExifInterface.TAG_F_NUMBER,
                            ExifInterface.TAG_EXPOSURE_TIME,
                            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                            ExifInterface.TAG_GPS_ALTITUDE,
                            ExifInterface.TAG_GPS_ALTITUDE_REF,
                            ExifInterface.TAG_FOCAL_LENGTH,
                            ExifInterface.TAG_GPS_DATESTAMP,
                            ExifInterface.TAG_WHITE_BALANCE,
                            ExifInterface.TAG_GPS_PROCESSING_METHOD,
                            ExifInterface.TAG_GPS_TIMESTAMP,
                            ExifInterface.TAG_DATETIME,
                            ExifInterface.TAG_FLASH,
                            ExifInterface.TAG_GPS_LATITUDE,
                            ExifInterface.TAG_GPS_LATITUDE_REF,
                            ExifInterface.TAG_GPS_LONGITUDE,
                            ExifInterface.TAG_GPS_LONGITUDE_REF,
                            ExifInterface.TAG_MAKE,
                            ExifInterface.TAG_MODEL,
                            ExifInterface.TAG_ORIENTATION);

            for (String tag : tags) {
                String attribute = sourceExif.getAttribute(tag);
                if (attribute != null) {
                    destinationExif.setAttribute(tag, attribute);
                }
            }

            destinationExif.saveAttributes();
        } catch (IOException e) {
            Log.e("ImageCrop", "Failed to preserve Exif information", e);
        }
    }

    private static final class ImageOptions {
        private final int width;
        private final int height;
        private final int degrees;

        ImageOptions(int width, int height, int degrees) {
            this.width = width;
            this.height = height;
            this.degrees = degrees;
        }

        int getHeight() {
            return (isFlippedDimensions() && degrees != 180) ? width : height;
        }

        int getWidth() {
            return (isFlippedDimensions() && degrees != 180)  ? height : width;
        }

        int getDegrees() {
            return degrees;
        }

        boolean isFlippedDimensions() {
            return degrees == 90 || degrees == 270 || degrees == 180;
        }

        public boolean isRotated() {
            return degrees != 0;
        }
    }
}
