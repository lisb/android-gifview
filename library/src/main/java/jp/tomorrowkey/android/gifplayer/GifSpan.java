package jp.tomorrowkey.android.gifplayer;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.os.AsyncTask;
import android.text.Editable;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.UiThread;

import java.io.InputStream;
import java.lang.ref.WeakReference;

import timber.log.Timber;

public class GifSpan extends ReplacementSpan {

	static final String TAG = "GifSpan";

	static final int IMAGE_TYPE_UNKNOWN = 0;
	static final int IMAGE_TYPE_DYNAMIC = 1;

	static final int DECODE_STATUS_UNDECODE = 0;
	static final int DECODE_STATUS_DECODING = 1;
	static final int DECODE_STATUS_DECODED = 2;

	/** Use if delay is negative */
	static final int SAFE_DELAY_MS = 100;

	GifDecoder decoder;

	int imageType = IMAGE_TYPE_UNKNOWN;
	int decodeStatus = DECODE_STATUS_UNDECODE;

	long startTime;
	long pauseTime;
	long length;

	boolean playFlag = false;

	final WeakReference<TextView> viewRef;
	final int resId;
	final int intrinsicWidth;
	final int intrinsicHeight;
	float scale;
	final float scaleToTextSize;

	public GifSpan(final TextView view, final int resId, final float scaleToTextSize) {
		this.viewRef = new WeakReference<>(view);
		this.resId = resId;
		this.scaleToTextSize = scaleToTextSize;

		final BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inJustDecodeBounds = true;
		BitmapFactory.decodeResource(view.getResources(), resId, opts);
		this.intrinsicWidth = opts.outWidth;
		this.intrinsicHeight = opts.outHeight;
	}

	@Override
	public int getSize(Paint paint, CharSequence text, int start, int end,
			FontMetricsInt fm) {
		final TextView view = viewRef.get();
		Timber.tag(TAG).v("getSize. isNull(view):%b", view == null);
		if (view == null) {
			return 0;
		}
		if (!text.equals(view.getText())) {
			return 0;
		}

		if (scaleToTextSize > 0) {
			final float textSize = paint.getTextSize();
			scale = scaleToTextSize * textSize / intrinsicHeight;
		} else {
			scale = getAutoScale(view.getResources());
		}

		if (fm != null) {
			fm.ascent = -Math.round(intrinsicHeight * scale);
			fm.descent = 0;

			fm.top = fm.ascent;
			fm.bottom = 0;
		}
		return Math.round(intrinsicWidth * scale);
	}

	@Override
	public void draw(Canvas canvas, CharSequence text, int start, int end,
			float x, int top, int y, int bottom, Paint paint) {
		final TextView view = viewRef.get();
		Timber.tag(TAG).v("draw. isNull(view):%b", view == null);
		if (view == null) {
			return;
		}
		if (!text.equals(view.getText())) {
			return;
		}

		if (decodeStatus == DECODE_STATUS_UNDECODE) {
			if (playFlag) {
				decode(view.getResources());
			}
		} else if (decodeStatus == DECODE_STATUS_DECODED) {
			if (imageType == IMAGE_TYPE_DYNAMIC) {
				if (decoder.frameCount <= 0) {
					return;
				}
				canvas.save();
				canvas.translate(x,
						bottom - Math.round(intrinsicHeight * scale));
				canvas.scale(scale, scale);
				if (decoder.frameCount == 1) {
					final Bitmap bitmap = decoder.getFrame(0);
					if (bitmap != null) {
						canvas.drawBitmap(bitmap, 0, 0, null);
					}
				} else if (decoder.frameCount > 1) {
					if (playFlag) {
						final long now = System.currentTimeMillis();
						long dt = (now - startTime) % length;
						for (int i = 0; i < decoder.frameCount; i++) {
							dt -= getSafeDelay(i);
							if (dt <= 0) {
								final Bitmap bitmap = decoder.getFrame(i);
								if (bitmap != null) {
									canvas.drawBitmap(bitmap, 0, 0, null);
								}
								if (dt == 0) {
									invalidateView(getSafeDelay((i + 1) % decoder.frameCount));
								} else {
									invalidateView(-dt);
								}
								break;
							}
						}
					} else {
						long dt = (pauseTime - startTime) % length;
						for (int i = 0; i < decoder.frameCount; i++) {
							dt -= getSafeDelay(i);
							if (dt <= 0) {
								final Bitmap bitmap = decoder.getFrame(i);
								if (bitmap != null) {
									canvas.drawBitmap(bitmap, 0, 0, null);
								}
								break;
							}
						}
					}
				}
				canvas.restore();
			}
		}
	}

	private int getSafeDelay(final int n) {
		final int delay = decoder.getDelay(n);
		if (delay > 0) {
			return delay;
		} else {
			return SAFE_DELAY_MS;
		}
	}

	@UiThread
	private void decode(Resources res) {
		decodeStatus = DECODE_STATUS_DECODING;
		new NewDecoderTask(res).execute();
	}

	private float getAutoScale(Resources res) {
		if (resId > 0) {
			final TypedValue value = new TypedValue();
			res.getValue(resId, value, false);

			if (value.density == TypedValue.DENSITY_NONE) {
				return 1;
			}

			return res.getDisplayMetrics().densityDpi / ((float) value.density);
		}

		return 0;
	}

	@UiThread
	public void start() {
		playFlag = true;
		// すでに進んでいる分を考慮。
		// ロードがまだ終わっていない場合、ロード時に startTime が改めて設定される
		startTime = System.currentTimeMillis() - (pauseTime - startTime);
		invalidateView(0);
	}

	@UiThread
	public void pause() {
		playFlag = false;
		pauseTime = System.currentTimeMillis();
		invalidateView(0);
	}

	private void invalidateView(final long delay) {
		final TextView view = viewRef.get();
		Timber.tag(TAG).v("invalidateView:%d, isNull(view):%b", delay, view == null);
		if (view == null) {
			return;
		}

		final Editable editableText = view.getEditableText();
		if (editableText != null && view.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
			// If text is editable, View#invalidate() doesn't re-draw GifSpan.
			view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
		}

		if (delay == 0) {
			view.invalidate();
		} else {
			view.postInvalidateDelayed(delay);
		}
	}

	private class NewDecoderTask extends AsyncTask<Void, Void, Void> {

		private final Resources res;
		private int newImageType;
		private long newTime;
		private GifDecoder newDecoder;

		NewDecoderTask(Resources res) {
			this.res = res;
		}

		@Override
		protected Void doInBackground(Void... params) {
			newDecoder = new GifDecoder();
			newDecoder.read(getInputStream());
			if (newDecoder.width == 0 || newDecoder.height == 0) {
				newImageType = IMAGE_TYPE_UNKNOWN;
			} else {
				newImageType = IMAGE_TYPE_DYNAMIC;
			}
			newTime = System.currentTimeMillis();
			return null;
		}

		private InputStream getInputStream() {
			if (resId > 0) {
				return res.openRawResource(resId);
			}
			return null;
		}

		@Override
		protected void onPostExecute(Void aVoid) {
			decoder = newDecoder;
			imageType = newImageType;
			startTime = newTime;
			decodeStatus = DECODE_STATUS_DECODED;
			long newLength = 0L;
			for (int i = 0; i < newDecoder.frameCount; i++) {
				newLength += getSafeDelay(i);
			}
			length = newLength;
			Timber.tag(TAG).v("Load completed. imageType:%s, frameCount:%d, length:%d",
					imageType, newDecoder.frameCount, length);
			invalidateView(0);
		}
	}
}
