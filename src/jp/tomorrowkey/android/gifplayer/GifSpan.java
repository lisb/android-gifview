package jp.tomorrowkey.android.gifplayer;

import java.io.InputStream;

import android.annotation.TargetApi;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.os.Build;
import android.os.Handler;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.View;

public class GifSpan extends ReplacementSpan implements Runnable {

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

	final Handler bgHandler;
	Handler uiHandler;
	final View view;
	final int resId;
	final int width;
	final int height;
	final float scale;

	public GifSpan(final Handler bgHandler, final View view, final int resId) {
		this.view = view;
		this.bgHandler = bgHandler;
		this.resId = resId;

		this.scale = getScale();

		final BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inJustDecodeBounds = true;
		BitmapFactory.decodeResource(view.getResources(), resId, opts);
		this.width = Math.round(opts.outWidth * this.scale);
		this.height = Math.round(opts.outHeight * this.scale);
		
		disableHardwareAccelation();
	}
	
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	private void disableHardwareAccelation() {
		// ハードウェアアクセレーションが走っているとEditableなTextViewでinvalidate()が動かないので、
		// 無効化する。
		if (Build.VERSION.SDK_INT >= 11) {
			view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
		}
	}

	@Override
	public int getSize(Paint paint, CharSequence text, int start, int end,
			FontMetricsInt fm) {
		if (fm != null) {
			fm.ascent = -height;
			fm.descent = 0;

			fm.top = fm.ascent;
			fm.bottom = 0;
		}
		return width;
	}

	@Override
	public void draw(Canvas canvas, CharSequence text, int start, int end,
			float x, int top, int y, int bottom, Paint paint) {
		if (decodeStatus == DECODE_STATUS_UNDECODE) {
			if (playFlag) {
				decode();
			}
		} else if (decodeStatus == DECODE_STATUS_DECODED) {
			if (imageType == IMAGE_TYPE_DYNAMIC) {
				canvas.save();
				canvas.translate(x, bottom - height);
				canvas.scale(scale, scale);
				if (playFlag) {
					long now = System.currentTimeMillis();

					if (time + decoder.getDelay(index) < now) {
						time += decoder.getDelay(index);
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

	private void decode() {
		index = 0;

		uiHandler = view.getHandler();
		decodeStatus = DECODE_STATUS_DECODING;

		bgHandler.post(this);
	}

	// バックグランドでdecodeを実行。
	@Override
	public void run() {
		final GifDecoder decoder = new GifDecoder();
		decoder.read(getInputStream());
		final int imageType;
		if (decoder.width == 0 || decoder.height == 0) {
			imageType = IMAGE_TYPE_UNKNOWN;
		} else {
			imageType = IMAGE_TYPE_DYNAMIC;
		}
		final long time = System.currentTimeMillis();
		final int decodeStatus = DECODE_STATUS_DECODED;

		uiHandler.post(new Runnable() {
			@Override
			public void run() {
				GifSpan.this.decoder = decoder;
				GifSpan.this.imageType = imageType;
				GifSpan.this.time = time;
				GifSpan.this.decodeStatus = decodeStatus;
				view.invalidate();
			}
		});
	};

	InputStream getInputStream() {
		if (resId > 0) {
			return view.getResources().openRawResource(resId);
		}
		return null;
	}

	float getScale() {
		if (resId > 0) {
			final TypedValue value = new TypedValue();
			final Resources res = view.getResources();
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

	public void start() {
		playFlag = true;
		view.invalidate();
	}

	public void pause() {
		playFlag = false;
		view.invalidate();
	}
}
