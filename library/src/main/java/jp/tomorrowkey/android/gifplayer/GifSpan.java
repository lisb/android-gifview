package jp.tomorrowkey.android.gifplayer;

import android.annotation.TargetApi;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.os.AsyncTask;
import android.os.Build;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.UiThread;

import java.io.InputStream;
import java.lang.ref.WeakReference;

public class GifSpan extends ReplacementSpan {

	static final String TAG = GifSpan.class.getSimpleName();

	static final int IMAGE_TYPE_UNKNOWN = 0;
	static final int IMAGE_TYPE_DYNAMIC = 1;

	static final int DECODE_STATUS_UNDECODE = 0;
	static final int DECODE_STATUS_DECODING = 1;
	static final int DECODE_STATUS_DECODED = 2;

	GifDecoder decoder;

	int imageType = IMAGE_TYPE_UNKNOWN;
	int decodeStatus = DECODE_STATUS_UNDECODE;

	long time;
	int index;

	boolean playFlag = false;

	final WeakReference<View> viewRef;
	final int resId;
	final int intrinsicWidth;
	final int intrinsicHeight;
	float scale;
	final float scaleToTextSize;

	public GifSpan(final TextView view, final int resId, final float scaleToTextSize) {
		this.viewRef = new WeakReference(view);
		this.resId = resId;
		this.scaleToTextSize = scaleToTextSize;

		final BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inJustDecodeBounds = true;
		BitmapFactory.decodeResource(view.getResources(), resId, opts);
		this.intrinsicWidth = opts.outWidth;
		this.intrinsicHeight = opts.outHeight;

		disableHardwareAccelation(view);
	}

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	private void disableHardwareAccelation(View view) {
		// ハードウェアアクセレーションが走っているとEditableなTextViewでinvalidate()が動かないので、
		// 無効化する。
		if (Build.VERSION.SDK_INT >= 11) {
			view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
		}
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
		final View view = validateView();
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
				canvas.save();
				canvas.translate(x,
						bottom - Math.round(intrinsicHeight * scale));
				canvas.scale(scale, scale);
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
					view.invalidate();
				} else {
					Bitmap bitmap = decoder.getFrame(index);
					canvas.drawBitmap(bitmap, 0, 0, null);
				}
				canvas.restore();
			}
		}
	}


	/**
	 * @return null if view is invalid.
     */
	@UiThread
	private View validateView() {
		final View view = viewRef.get();
		if (view == null) {
			Log.w(TAG, "No view reference.");
			return null;
		}

		if (view.getResources() == null) {
			Log.w(TAG, "View has no resources. ");
			return null;
		}

		return view;
	}

	@UiThread
	private void decode(Resources res) {
		index = 0;
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

	private void incrementFrameIndex() {
		index++;
		if (index >= decoder.getFrameCount()) {
			index = 0;
		}
	}

	@UiThread
	private void destroy() {
		Log.d(TAG, "destroy");
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
		view.invalidate();
	}

	@UiThread
	public void pause() {
		playFlag = false;
		final View view = validateView();
		if (view == null) {
			destroy();
			return;
		}
		view.invalidate();
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
				view.invalidate();
				decoder = newDecoder;
				imageType = newImageType;
				time = newTime;
				decodeStatus = DECODE_STATUS_DECODED;
			}
		}
	}
}
