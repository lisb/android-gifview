package jp.tomorrowkey.android.gifplayer;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.os.AsyncTask;
import android.text.Editable;
import android.text.Spannable;
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

	private final Runnable replaceSpanCmd = new Runnable() {
		@Override
		public void run() {
			replaceSpan();
		}
	};

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
		final View view = validateView();
		if (view == null) {
			destroy();
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
		final TextView view = validateView();
		if (view == null) {
			destroy();
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
									viewInvalidate(getSafeDelay((i + 1) % decoder.frameCount));
								} else {
									viewInvalidate(-dt);
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

	/**
	 * @return null if view is invalid.
     */
	@UiThread
	private TextView validateView() {
		final TextView view = viewRef.get();
		if (view == null) {
			Timber.tag(TAG).w("No view reference.");
			return null;
		}

		if (view.getResources() == null) {
			Timber.tag(TAG).w("View has no resources.");
			return null;
		}

		final CharSequence currentText = view.getText();
		final boolean isSpannable = currentText instanceof Spannable;
		if (!isSpannable) {
			Timber.tag(TAG).w("View text is not Spannable.");
			return null;
		}

		final Spannable currentTextSpannable = (Spannable) currentText;
		final int spanStart = currentTextSpannable.getSpanStart(this);
		if (spanStart == -1) {
			Timber.tag(TAG).w("View text doesn't contain this span");
			return null;
		}

		return view;
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
	private void destroy() {
		Timber.tag(TAG).v("destroy");
		decoder = null;
	}

	@UiThread
	public void start() {
		playFlag = true;
		final View view = validateView();
		if (view == null) {
			destroy();
			return;
		}
		// すでに進んでいる分を考慮。
		// ロードがまだ終わっていない場合、ロード時に startTime が改めて設定される
		startTime = System.currentTimeMillis() - (pauseTime - startTime);
		viewInvalidate(0);
	}

	@UiThread
	public void pause() {
		playFlag = false;
		final View view = validateView();
		if (view == null) {
			destroy();
			return;
		}
		pauseTime = System.currentTimeMillis();
		viewInvalidate(0);
	}

	private void replaceSpan() {
		final TextView view = validateView();
		if (view == null) {
			return;
		}

		final Editable editableText = view.getEditableText();
		if (editableText == null) {
			return;
		}

		final int spanStart = editableText.getSpanStart(this);
		if (spanStart == -1) {
			return;
		}

		final int spanEnd = editableText.getSpanEnd(this);
		final int spanFlags = editableText.getSpanFlags(this);
		editableText.removeSpan(this);
		editableText.setSpan(this, spanStart, spanEnd, spanFlags);
		view.removeCallbacks(replaceSpanCmd);
	}

	private void viewInvalidate(final long delay) {
		final TextView view = validateView();
		if (view == null) {
			return;
		}

		final Editable editableText = view.getEditableText();
		if (editableText != null) {
			// If text is Editable, TextView#invalidate don't cause a redraw
			if (delay == 0) {
				replaceSpan();
			} else {
				view.postDelayed(replaceSpanCmd, delay);
			}
		} else {
			if (delay == 0) {
				view.invalidate();
			} else {
				view.postInvalidateDelayed(delay);
			}
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
			final View view = validateView();
			if (view != null) {
				viewInvalidate(0);
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
			}
		}
	}
}
