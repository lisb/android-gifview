package jp.tomorrowkey.android.gifplayer;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;

import timber.log.Timber;

public class GifView extends View {

    private static final String TAG = "GifView";

	public static final int IMAGE_TYPE_UNKNOWN = 0;
	public static final int IMAGE_TYPE_STATIC = 1;
	public static final int IMAGE_TYPE_DYNAMIC = 2;

	public static final int DECODE_STATUS_UNDECODE = 0;
	public static final int DECODE_STATUS_DECODING = 1;
	public static final int DECODE_STATUS_DECODED = 2;

	private GifDecoder decoder;
	private Bitmap bitmap;

	public int imageType = IMAGE_TYPE_UNKNOWN;
	public int decodeStatus = DECODE_STATUS_UNDECODE;

	private int intrinsicWidth; // autoScale倍済み
	private int intrinsicHeight; // autoScale倍済み
	private boolean fitCenter; // viewのサイズに合わせて拡大する

	private long time;
	private int index;

	/**
	 * resourceからDrawableを呼び出した際と同じscale。 fileからデータを取得した場合やcacheImageには適用されない。
	 */
	private float autoScale;
	private int resId;
	private String filePath;

	private static Handler bgHandler;
	private Handler uiHandler;

	private DecodeTask decodeTask;

	private boolean playFlag = false;

	public GifView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	/**
	 * Constructor
	 */
	public GifView(Context context) {
		super(context);
	}

	public static void setBgHandler(Handler bgHandler) {
		GifView.bgHandler = bgHandler;
	}

	private InputStream getInputStream() {
		if (filePath != null)
			try {
				return new FileInputStream(filePath);
			} catch (FileNotFoundException e) {
			}
		if (resId > 0)
			return getContext().getResources().openRawResource(resId);
		return null;
	}

	float getAutoScale() {
		if (filePath != null) {
			return 1.0f;
		}

		if (resId > 0) {
			final TypedValue value = new TypedValue();
			final Resources res = getContext().getResources();
			res.getValue(resId, value, false);

			if (value.density == TypedValue.DENSITY_NONE) {
				return 1;
			}

            if (value.density == TypedValue.DENSITY_DEFAULT) {
                return res.getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT;
            }

			return res.getDisplayMetrics().densityDpi / ((float) value.density);
		}

		return 0;
	}

	private void fitCenter(final Canvas canvas) {
		final int coreWidth = getWidth() - getPaddingLeft() - getPaddingRight();
		final int coreHeight = getHeight() - getPaddingTop()
				- getPaddingBottom();
		final float widthScale = ((float) coreWidth) / intrinsicWidth;
		final float heightScale = ((float) coreHeight) / intrinsicHeight;
		final float scale = Math.min(widthScale, heightScale);

		final float dx = (coreWidth - intrinsicWidth * scale) / 2;
		final float dy = (coreHeight - intrinsicHeight * scale) / 2;

		canvas.translate(dx, dy);
		canvas.scale(scale, scale);
	}

	/**
	 * set gif file path
	 * 
	 * @param filePath
	 */
	public void setGif(String filePath) {
		Bitmap bitmap = BitmapFactory.decodeFile(filePath);
		setGif(filePath, bitmap);
	}

	/**
	 * set gif file path and cache image
	 * 
	 * @param filePath
	 * @param cacheImage
	 */
	public void setGif(String filePath, Bitmap cacheImage) {
		this.resId = 0;
		this.filePath = filePath;
		imageType = IMAGE_TYPE_UNKNOWN;
		decodeStatus = DECODE_STATUS_UNDECODE;
		playFlag = false;
		bitmap = cacheImage;
		intrinsicWidth = bitmap.getWidth();
		intrinsicHeight = bitmap.getHeight();
	}

	/**
	 * NOTE: Viewのサイズが両方不定の場合はfitCenter==falseと同じ動きになる。
	 */
	public void setfitCenter(final boolean fitCenter) {
		this.fitCenter = fitCenter;
	}

	/**
	 * set gif resource id
	 * 
	 * @param resId
	 */
	public void setGif(int resId) {
		Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resId);
		setGif(resId, bitmap);
	}

	/**
	 * set gif resource id and cache image
	 * 
	 * @param resId
	 * @param cacheImage
	 */
	public void setGif(int resId, Bitmap cacheImage) {
		this.filePath = null;
		this.resId = resId;
		imageType = IMAGE_TYPE_UNKNOWN;
		decodeStatus = DECODE_STATUS_UNDECODE;
		playFlag = false;
		bitmap = cacheImage;
		intrinsicWidth = bitmap.getWidth();
		intrinsicHeight = bitmap.getHeight();

		Timber.tag(TAG).d("gif set. intrinsicWidth:%d, intrinsicHeight:%d",
				intrinsicWidth, intrinsicHeight);
	}

	// attachされていない状態では呼び出せない
	private void decode() {
		release();
		index = 0;

		uiHandler = getHandler();
		decodeStatus = DECODE_STATUS_DECODING;
		if (decodeTask != null) {
			bgHandler.removeCallbacks(decodeTask);
			uiHandler.removeCallbacks(decodeTask);
		}
		decodeTask = new DecodeTask(resId);
		bgHandler.post(decodeTask);
	}

	private class DecodeTask implements Runnable {

		private final int resId;
		private float autoScale;
		private GifDecoder decoder;
		private int imageType;
		private long time;
		private int decodeStatus;

		public DecodeTask(final int resId) {
			this.resId = resId;
		}

		@Override
		public void run() {
			if (Thread.currentThread() != uiHandler.getLooper().getThread()) {
				// worker threadでのデコード
				autoScale = getAutoScale();
				decoder = new GifDecoder();
				decoder.read(getInputStream());
				if (decoder.width == 0 || decoder.height == 0) {
					imageType = IMAGE_TYPE_STATIC;
				} else {
					imageType = IMAGE_TYPE_DYNAMIC;
				}
				time = System.currentTimeMillis();
				decodeStatus = DECODE_STATUS_DECODED;
				uiHandler.post(this);
			} else {
				// ui threadでのフィールドへのデータの適用
				if (decodeTask != this) {
					return;
				}

				decodeTask = null;
				GifView.this.resId = this.resId;
				GifView.this.autoScale = this.autoScale;
				GifView.this.decoder = this.decoder;
				GifView.this.imageType = this.imageType;
				GifView.this.time = this.time;
				GifView.this.decodeStatus = this.decodeStatus;
				invalidate();
			}

		}

	}

	public void release() {
		decoder = null;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
		final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		final int width = MeasureSpec.getSize(widthMeasureSpec);
		final int height = MeasureSpec.getSize(heightMeasureSpec);
		final int paddingWidth = getPaddingLeft() + getPaddingRight();
		final int paddingHeight = getPaddingTop() + getPaddingBottom();

		if (fitCenter) {
			if (widthMode == MeasureSpec.UNSPECIFIED
					&& heightMode == MeasureSpec.UNSPECIFIED) {
				setMeasuredDimension(this.intrinsicWidth + paddingWidth,
						this.intrinsicHeight + paddingHeight);
			} else if (widthMode == MeasureSpec.UNSPECIFIED) {
				setMeasuredDimension(this.intrinsicWidth
						* (height - paddingHeight) / this.intrinsicHeight,
						height);
			} else if (heightMode == MeasureSpec.UNSPECIFIED) {
				setMeasuredDimension(width,
						(this.intrinsicHeight * width - paddingWidth)
								/ this.intrinsicWidth);
            } else {
                setMeasuredDimension(measureSize(this.intrinsicWidth + paddingWidth,
                        widthMeasureSpec), measureSize(this.intrinsicHeight + paddingHeight,
                        heightMeasureSpec));
            }
        } else {
            setMeasuredDimension(measureSize(this.intrinsicWidth + paddingWidth,
                    widthMeasureSpec), measureSize(this.intrinsicHeight + paddingHeight,
                    heightMeasureSpec));
        }
		Timber.tag(TAG).v("onMeasured. widthMode:%d, heightMode:%d, width:%d, height:%d, instrinsicWidth:%d, instrinsicHeight:%d, measuredWidth:%d, measuredHeight:%d",
				widthMode, heightMode, width, height, intrinsicWidth, intrinsicHeight,
				getMeasuredWidth(), getMeasuredHeight());
	}

    private static int measureSize(int instrinsicSize, int measureSpec) {
        final int mode = MeasureSpec.getMode(measureSpec);
        final int size = MeasureSpec.getSize(measureSpec);

        switch (mode) {
            case MeasureSpec.EXACTLY:
                return size;
            case MeasureSpec.AT_MOST:
                return instrinsicSize <= size ? instrinsicSize : size;
            default:
                return instrinsicSize;
        }
    }

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		canvas.save();
		canvas.translate(getPaddingLeft(), getPaddingTop());
		if (fitCenter) {
			fitCenter(canvas);
		}
		if (decodeStatus == DECODE_STATUS_UNDECODE) {
            if (bitmap != null) {
                // layout ファイルのプレビューでエラーが出ないようにしている。
                canvas.drawBitmap(bitmap, 0, 0, null);
            }
			if (playFlag) {
				decode();
				invalidate();
			}
		} else if (decodeStatus == DECODE_STATUS_DECODING) {
			canvas.drawBitmap(bitmap, 0, 0, null);
			invalidate();
		} else if (decodeStatus == DECODE_STATUS_DECODED) {
			if (imageType == IMAGE_TYPE_STATIC) {
				canvas.drawBitmap(bitmap, 0, 0, null);
			} else if (imageType == IMAGE_TYPE_DYNAMIC) {
				canvas.scale(autoScale, autoScale);
				if (playFlag) {
					long now = System.currentTimeMillis();
					
					if (time + decoder.getDelay(index) < now) {
						// TODO indexを一つインクリメントするだけではなく、正しい位置までindexを増やすようにする
						time = now;
						incrementFrameIndex();
					}
					Bitmap bitmap = decoder.getFrame(index);
					if (bitmap != null) {
						canvas.drawBitmap(bitmap, 0, 0, null);
					}
					invalidate();
				} else {
					Bitmap bitmap = decoder.getFrame(index);
					canvas.drawBitmap(bitmap, 0, 0, null);
				}
			} else {
				canvas.drawBitmap(bitmap, 0, 0, null);
			}
		}
		canvas.restore();
	}

	private void incrementFrameIndex() {
		index++;
		if (index >= decoder.getFrameCount()) {
			index = 0;
		}
	}

	private void decrementFrameIndex() {
		index--;
		if (index < 0) {
			index = decoder.getFrameCount() - 1;
		}
	}

	public void play() {
		time = System.currentTimeMillis();
		playFlag = true;
		invalidate();
	}

	public void pause() {
		playFlag = false;
		invalidate();
	}

	public void stop() {
		playFlag = false;
		index = 0;
		invalidate();
	}

	public void nextFrame() {
		if (decodeStatus == DECODE_STATUS_DECODED) {
			incrementFrameIndex();
			invalidate();
		}
	}

	public void prevFrame() {
		if (decodeStatus == DECODE_STATUS_DECODED) {
			decrementFrameIndex();
			invalidate();
		}
	}
}