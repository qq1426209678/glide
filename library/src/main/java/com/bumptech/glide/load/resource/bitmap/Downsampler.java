package com.bumptech.glide.load.resource.bitmap;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import com.bumptech.glide.Logs;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.util.ByteArrayPool;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Downsamples, decodes, and rotates images according to their exif orientation.
 */
public final class Downsampler {
  private static final String TAG = "Downsampler";
  /**
   * A key for an {@link com.bumptech.glide.load.DecodeFormat} option that will be used in
   * conjunction with the image format to determine the {@link android.graphics.Bitmap.Config} to
   * provide to {@link android.graphics.BitmapFactory.Options#inPreferredConfig} when decoding
   * the image.
   */
  public static final String KEY_DECODE_FORMAT =
      "com.bumptech.glide.load.resource.bitmap.Downsampler.DecodeFormat";
  /**
   * A key for an {@link com.bumptech.glide.load.resource.bitmap.DownsampleStrategy} option that
   * will be used to calculate the sample size to use to downsample an image given the original
   * and target dimensions of the image.
   */
  public static final String KEY_DOWNSAMPLE_STRATEGY =
      "com.bumptech.glide.load.resource.bitmap.Downsampler.DownsampleStrategy";

  private static final DecodeCallbacks EMPTY_CALLBACKS = new DecodeCallbacks() {
    @Override
    public void onObtainBounds() {
      // Do nothing.
    }

    @Override
    public void onDecodeComplete(BitmapPool bitmapPool, Bitmap downsampled) throws IOException {
      // Do nothing.
    }
  };
  private static final Set<ImageHeaderParser.ImageType> TYPES_THAT_USE_POOL_PRE_KITKAT =
      Collections.unmodifiableSet(
          EnumSet.of(
              ImageHeaderParser.ImageType.JPEG,
              ImageHeaderParser.ImageType.PNG_A,
              ImageHeaderParser.ImageType.PNG
          )
  );
  private static final Queue<BitmapFactory.Options> OPTIONS_QUEUE = Util.createQueue(0);
  // 5MB. This is the max image header size we can handle, we preallocate a much smaller buffer
  // but will resize up to this amount if necessary.
  private static final int MARK_POSITION = 5 * 1024 * 1024;

  private final BitmapPool bitmapPool;

  public Downsampler(BitmapPool bitmapPool) {
    this.bitmapPool = Preconditions.checkNotNull(bitmapPool);
  }

  public boolean handles(InputStream is) {
    // We expect Downsampler to handle any available type Android supports.
    return true;
  }

  public boolean handles(ByteBuffer byteBuffer) {
    // We expect downsampler to handle any available type Android supports.
    return true;
  }

  /**
   * Returns a Bitmap decoded from the given {@link InputStream} that is rotated to match any EXIF
   * data present in the stream and that is downsampled according to the given dimensions and any
   * provided  {@link com.bumptech.glide.load.resource.bitmap.DownsampleStrategy} option.
   *
   * @see #decode(java.io.InputStream, int, int, java.util.Map,
   * com.bumptech.glide.load.resource.bitmap.Downsampler.DecodeCallbacks)
   */
  public Resource<Bitmap> decode(InputStream is, int outWidth, int outHeight,
      Map<String, Object> options) throws IOException {
    return decode(is, outWidth, outHeight, options, EMPTY_CALLBACKS);
  }

  /**
   * Returns a Bitmap decoded from the given {@link InputStream} that is rotated to match any EXIF
   * data present in the stream and that is downsampled according to the given dimensions and any
   * provided  {@link com.bumptech.glide.load.resource.bitmap.DownsampleStrategy} option.
   *
   * <p> If a Bitmap is present in the
   * {@link com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool} whose dimensions exactly match
   * those of the image for the given InputStream is available, the operation is much less expensive
   * in terms of memory. </p>
   *
   * <p> The provided {@link java.io.InputStream} must return <code>true</code> from
   * {@link java.io.InputStream#markSupported()} and is expected to support a reasonably large
   * mark limit to accommodate reading large image headers (~5MB). </p>
   *
   * @param is        An {@link InputStream} to the data for the image.
   * @param requestedWidth  The width the final image should be close to.
   * @param requestedHeight The height the final image should be close to.
   * @param options   A set of options that may contain one or more supported options that influence
   *                  how a Bitmap will be decoded from the given stream.
   * @param callbacks A set of callbacks allowing callers to optionally respond to various
   *                  significant events during the decode process.
   * @return A new bitmap containing the image from the given InputStream, or recycle if recycle is
   * not null.
   */
  @SuppressWarnings("resource")
  public Resource<Bitmap> decode(InputStream is, int requestedWidth, int requestedHeight,
      Map<String, Object> options, DecodeCallbacks callbacks) throws IOException {
    Preconditions.checkArgument(is.markSupported(), "You must provide an InputStream that supports"
        + " mark()");

    ByteArrayPool byteArrayPool = ByteArrayPool.get();
    byte[] bytesForOptions = byteArrayPool.getBytes();
    BitmapFactory.Options bitmapFactoryOptions = getDefaultOptions();
    bitmapFactoryOptions.inTempStorage = bytesForOptions;

    DecodeFormat decodeFormat = getDecodeFormat(options);
    DownsampleStrategy downsampleStrategy = getDownsampleStrategy(options);

    try {
      Bitmap result = decodeFromWrappedStreams(is, bitmapFactoryOptions,
          downsampleStrategy, decodeFormat, requestedWidth, requestedHeight, callbacks);
      return BitmapResource.obtain(result, bitmapPool);
    } finally {
      releaseOptions(bitmapFactoryOptions);
      byteArrayPool.releaseBytes(bytesForOptions);
    }
  }

  private Bitmap decodeFromWrappedStreams(InputStream is,
      BitmapFactory.Options options, DownsampleStrategy downsampleStrategy,
      DecodeFormat decodeFormat, int requestedWidth, int requestedHeight,
      DecodeCallbacks callbacks) throws IOException {

    int[] sourceDimensions = getDimensions(is, options, callbacks);
    int sourceWidth = sourceDimensions[0];
    int sourceHeight = sourceDimensions[1];
    String sourceMimeType = options.outMimeType;

    int orientation = getOrientation(is);
    int degreesToRotate = TransformationUtils.getExifOrientationDegrees(getOrientation(is));

    options.inPreferredConfig = getConfig(is, decodeFormat);
    options.inSampleSize = getRoundedSampleSize(downsampleStrategy, degreesToRotate,
        sourceWidth, sourceHeight, requestedWidth, requestedHeight);
    options.inDensity = downsampleStrategy.getDensity(sourceWidth, sourceHeight,
        requestedWidth, requestedHeight);
    options.inTargetDensity = downsampleStrategy.getTargetDensity(sourceWidth,
        sourceHeight, requestedWidth, requestedHeight, options.inSampleSize);
    if (isScaling(options)) {
      options.inScaled = true;
    }
    Bitmap downsampled = downsampleWithSize(is, options, bitmapPool, sourceWidth,
        sourceHeight, callbacks);
    callbacks.onDecodeComplete(bitmapPool, downsampled);

    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      logDecode(sourceWidth, sourceHeight, sourceMimeType, options, downsampled,
          requestedWidth, requestedHeight);
    }

    Bitmap rotated = null;
    if (downsampled != null) {
      rotated = TransformationUtils.rotateImageExif(downsampled, bitmapPool, orientation);
      if (!downsampled.equals(rotated) && !bitmapPool.put(downsampled)) {
        downsampled.recycle();
      }
    }

    return rotated;
  }

  private int getRoundedSampleSize(DownsampleStrategy downsampleStrategy, int degreesToRotate,
      int sourceWidth, int sourceHeight, int requestedWidth, int requestedHeight) {
    int targetHeight = requestedHeight == Target.SIZE_ORIGINAL ? sourceHeight : requestedHeight;
    int targetWidth = requestedWidth == Target.SIZE_ORIGINAL ? sourceWidth : requestedWidth;

    final int exactSampleSize;
    if (degreesToRotate == 90 || degreesToRotate == 270) {
      // If we're rotating the image +-90 degrees, we need to downsample accordingly so the image
      // width is decreased to near our target's height and the image height is decreased to near
      // our target width.
      exactSampleSize =
          downsampleStrategy.getSampleSize(sourceHeight, sourceWidth, targetWidth, targetHeight);
    } else {
      exactSampleSize =
          downsampleStrategy.getSampleSize(sourceWidth, sourceHeight, targetWidth, targetHeight);
    }

    // BitmapFactory only accepts powers of 2, so it will round down to the nearest power of two
    // that is less than or equal to the sample size we provide. Because we need to estimate the
    // final image width and height to re-use Bitmaps, we mirror BitmapFactory's calculation here.
    // For bug, see issue #224. For algorithm see http://stackoverflow.com/a/17379704/800716.
    final int powerOfTwoSampleSize =
        exactSampleSize == 0 ? 0 : Integer.highestOneBit(exactSampleSize);

    // Although functionally equivalent to 0 for BitmapFactory, 1 is a safer default for our code
    // than 0.
    return Math.max(1, powerOfTwoSampleSize);
  }

  private static DownsampleStrategy getDownsampleStrategy(Map<String, Object> options) {
    return options.containsKey(KEY_DOWNSAMPLE_STRATEGY)
        ? (DownsampleStrategy) options.get(KEY_DOWNSAMPLE_STRATEGY) : DownsampleStrategy.DEFAULT;
  }

  private static DecodeFormat getDecodeFormat(Map<String, Object> options) {
    return options.containsKey(KEY_DECODE_FORMAT)
        ? (DecodeFormat) options.get(KEY_DECODE_FORMAT) : DecodeFormat.DEFAULT;
  }

  private static int getOrientation(InputStream is) throws IOException {
    is.mark(MARK_POSITION);
    int orientation = 0;
    try {
      orientation = new ImageHeaderParser(is).getOrientation();
    } catch (IOException e) {
      if (Logs.isEnabled(Log.DEBUG)) {
        Logs.log(Log.DEBUG, "Cannot determine the image orientation from header", e);
      }
    } finally {
      is.reset();
    }
    return orientation;
  }

  private static Bitmap downsampleWithSize(InputStream is, BitmapFactory.Options options,
      BitmapPool pool, int sourceWidth, int sourceHeight, DecodeCallbacks callbacks)
      throws IOException {
    // Prior to KitKat, the inBitmap size must exactly match the size of the bitmap we're decoding.
    if ((options.inSampleSize == 1 || Build.VERSION_CODES.KITKAT <= Build.VERSION.SDK_INT)
        && shouldUsePool(is)) {
      // If we have valid densities, scale, but make sure we don't upscale.
      float densityMultiplier = isScaling(options)
          ? Math.min((float) options.inTargetDensity / options.inDensity, 1f) : 1f;

      int sampleSize = options.inSampleSize;
      int downsampledWidth = sourceWidth / sampleSize;
      int downsampledHeight = sourceHeight / sampleSize;
      int expectedWidth = (int) Math.ceil(downsampledWidth * densityMultiplier);
      int expectedHeight = (int) Math.ceil(downsampledHeight * densityMultiplier);

      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(TAG, "Calculated target [" + expectedWidth + "x" + expectedHeight + "] for source"
            + "[" + sourceWidth + "x" + sourceHeight + "]"
            + ", sampleSize: " + sampleSize
            + ", density multiplier: " + densityMultiplier);
      }
      // BitmapFactory will clear out the Bitmap before writing to it, so getDirty is safe.
      setInBitmap(options, pool.getDirty(expectedWidth, expectedHeight, options.inPreferredConfig));
    }
    return decodeStream(is, options, callbacks);
  }

  private static boolean shouldUsePool(InputStream is) throws IOException {
    // On KitKat+, any bitmap (of a given config) can be used to decode any other bitmap
    // (with the same config).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      return true;
    }

    is.mark(MARK_POSITION);
    try {
      final ImageHeaderParser.ImageType type = new ImageHeaderParser(is).getType();
      // We cannot reuse bitmaps when decoding images that are not PNG or JPG prior to KitKat.
      // See: https://groups.google.com/forum/#!msg/android-developers/Mp0MFVFi1Fo/e8ZQ9FGdWdEJ
      return TYPES_THAT_USE_POOL_PRE_KITKAT.contains(type);
    } catch (IOException e) {
      if (Logs.isEnabled(Log.DEBUG)) {
        Logs.log(Log.DEBUG, "Cannot determine the image type from header", e);
      }
    } finally {
      is.reset();
    }
    return false;
  }

  private static Bitmap.Config getConfig(InputStream is, DecodeFormat format) throws IOException {
    // Changing configs can cause skewing on 4.1, see issue #128.
    if (format == DecodeFormat.PREFER_ARGB_8888
        || Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN) {
      return Bitmap.Config.ARGB_8888;
    }

    boolean hasAlpha = false;
    is.mark(MARK_POSITION);
    try {
      hasAlpha = new ImageHeaderParser(is).hasAlpha();
    } catch (IOException e) {
      if (Logs.isEnabled(Log.DEBUG)) {
        Logs.log(Log.DEBUG, "Cannot determine whether the image has alpha or not from header for"
            + " format " + format, e);
      }
    } finally {
      is.reset();
    }

    return hasAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
  }

  /**
   * A method for getting the dimensions of an image from the given InputStream.
   *
   * @param is      The InputStream representing the image.
   * @param options The options to pass to {@link BitmapFactory#decodeStream(java.io.InputStream,
   *                android.graphics.Rect, android.graphics.BitmapFactory.Options)}.
   * @return an array containing the dimensions of the image in the form {width, height}.
   */
  private static int[] getDimensions(InputStream is, BitmapFactory.Options options,
      DecodeCallbacks decodeCallbacks) throws IOException {
    options.inJustDecodeBounds = true;
    decodeStream(is, options, decodeCallbacks);
    options.inJustDecodeBounds = false;
    return new int[] { options.outWidth, options.outHeight };
  }

  private static Bitmap decodeStream(InputStream is, BitmapFactory.Options options,
      DecodeCallbacks callbacks) throws IOException {
    if (options.inJustDecodeBounds) {
      is.mark(MARK_POSITION);
    }
    // BitmapFactory.Options out* variables are reset by most calls to decodeStream, successful or
    // otherwise, so capture here in case we log below.
    int sourceWidth = options.outWidth;
    int sourceHeight = options.outHeight;
    String outMimeType = options.outMimeType;
    final Bitmap result;
    try {
      result = BitmapFactory.decodeStream(is, null, options);
    } catch (IllegalArgumentException e) {
      throw newIoExceptionForInBitmapAssertion(e, sourceWidth, sourceHeight, outMimeType, options);
    }

    if (options.inJustDecodeBounds) {
      is.reset();
      // Once we've read the image header, we no longer need to allow the buffer to expand in
      // size. To avoid unnecessary allocations reading image data, we fix the mark limit so that it
      // is no larger than our current buffer size here. See issue #225.
      callbacks.onObtainBounds();
    }

    return result;
  }

  private static boolean isScaling(BitmapFactory.Options options) {
    return options.inTargetDensity > 0 && options.inDensity > 0
        && options.inTargetDensity != options.inDensity;
  }

  private static void logDecode(int sourceWidth, int sourceHeight, String outMimeType,
      BitmapFactory.Options options, Bitmap result, int requestedWidth, int requestedHeight) {
    Log.v(TAG, "Decoded " + getBitmapString(result)
        + " from [" + sourceWidth + "x" + sourceHeight + "] " + outMimeType
        + " with inBitmap " + getInBitmapString(options)
        + " for [" + requestedWidth + "x" + requestedHeight + "]"
        + ", sample size: " + options.inSampleSize
        + ", density: " + options.inDensity
        + ", target density: " + options.inTargetDensity
        + ", thread: " + Thread.currentThread().getName());
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private static String getInBitmapString(BitmapFactory.Options options) {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
        ? getBitmapString(options.inBitmap) : null;
  }

  @TargetApi(Build.VERSION_CODES.KITKAT)
  private static String getBitmapString(Bitmap bitmap) {
    final String result;
    if (bitmap == null) {
      result = null;
    } else {
      String sizeString = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
          ? " (" + bitmap.getAllocationByteCount() + ")" : "";
      result = "[" + bitmap.getWidth() + "x" + bitmap.getHeight() + "] " + bitmap.getConfig()
          + sizeString;
    }
    return result;
  }

  // BitmapFactory throws an IllegalArgumentException if any error occurs attempting to decode a
  // file when inBitmap is non-null, including those caused by partial or corrupt data. We still log
  // the error because the IllegalArgumentException is supposed to catch errors reusing Bitmaps, so
  // want some useful log output. In most cases this can be safely treated as a normal IOException.
  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private static IOException newIoExceptionForInBitmapAssertion(IllegalArgumentException e,
      int outWidth, int outHeight, String outMimeType, BitmapFactory.Options options) {
    return new IOException("Exception decoding bitmap"
          + ", outWidth: " + outWidth
          + ", outHeight: " + outHeight
          + ", outMimeType: " + outMimeType
          + ", inBitmap: " + getInBitmapString(options), e);
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private static void setInBitmap(BitmapFactory.Options options, Bitmap recycled) {
    if (Build.VERSION_CODES.HONEYCOMB <= Build.VERSION.SDK_INT) {
      options.inBitmap = recycled;
    }
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private static synchronized BitmapFactory.Options getDefaultOptions() {
    BitmapFactory.Options decodeBitmapOptions;
    synchronized (OPTIONS_QUEUE) {
      decodeBitmapOptions = OPTIONS_QUEUE.poll();
    }
    if (decodeBitmapOptions == null) {
      decodeBitmapOptions = new BitmapFactory.Options();
      resetOptions(decodeBitmapOptions);
    }

    return decodeBitmapOptions;
  }

  private static void releaseOptions(BitmapFactory.Options decodeBitmapOptions) {
    resetOptions(decodeBitmapOptions);
    synchronized (OPTIONS_QUEUE) {
      OPTIONS_QUEUE.offer(decodeBitmapOptions);
    }
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private static void resetOptions(BitmapFactory.Options decodeBitmapOptions) {
    decodeBitmapOptions.inTempStorage = null;
    decodeBitmapOptions.inDither = false;
    decodeBitmapOptions.inScaled = false;
    decodeBitmapOptions.inSampleSize = 1;
    decodeBitmapOptions.inPreferredConfig = null;
    decodeBitmapOptions.inJustDecodeBounds = false;
    decodeBitmapOptions.inDensity = 0;
    decodeBitmapOptions.inTargetDensity = 0;
    decodeBitmapOptions.outWidth = 0;
    decodeBitmapOptions.outHeight = 0;
    decodeBitmapOptions.outMimeType = null;

    if (Build.VERSION_CODES.HONEYCOMB <= Build.VERSION.SDK_INT) {
      decodeBitmapOptions.inBitmap = null;
      decodeBitmapOptions.inMutable = true;
    }
  }

  /**
   * Callbacks for key points during decodes.
   */
  public interface DecodeCallbacks {
    void onObtainBounds();
    void onDecodeComplete(BitmapPool bitmapPool, Bitmap downsampled) throws IOException;
  }
}
