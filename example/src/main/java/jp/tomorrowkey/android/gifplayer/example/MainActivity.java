package jp.tomorrowkey.android.gifplayer.example;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

import jp.tomorrowkey.android.gifplayer.GifSpan;
import jp.tomorrowkey.android.gifplayer.GifView;
import timber.log.Timber;

public class MainActivity extends Activity implements OnClickListener, TextWatcher {

	private static final String TAG = "MainActivity";

	private GifView gifView;
	private EditText editText;

	static {
		Timber.plant(new Timber.DebugTree());
		final HandlerThread thread = new HandlerThread("gifplayer-background");
		thread.start();
		GifView.setBgHandler(new Handler(thread.getLooper()));
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		gifView = findViewById(R.id.gifView);
		final Button btnPlay = findViewById(R.id.btnPlay);
		final Button btnPause = findViewById(R.id.btnPause);
		final Button btnStop = findViewById(R.id.btnStop);
		final Button btnPrevFrame = findViewById(R.id.btnPrevFrame);
		final Button btnNextFrame = findViewById(R.id.btnNextFrame);
		editText = findViewById(R.id.editText);

		gifView.setGif(R.drawable.break_droid);
		btnPlay.setOnClickListener(this);
		btnPause.setOnClickListener(this);
		btnStop.setOnClickListener(this);
		btnPrevFrame.setOnClickListener(this);
		btnNextFrame.setOnClickListener(this);

		editText.addTextChangedListener(this);
	}

	@Override
	public void onClick(View v) {
		int id = v.getId();
		if (id == R.id.btnPlay) {
			gifView.play();
		} else if (id == R.id.btnPause) {
			gifView.pause();
		} else if (id == R.id.btnStop) {
			gifView.stop();
		} else if (id == R.id.btnPrevFrame) {
			gifView.prevFrame();
		} else if (id == R.id.btnNextFrame) {
			gifView.nextFrame();
		}
	}

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count, int after) {
	}

	@Override
	public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
		Timber.tag(TAG).d("onTextChanged. start:%d, before:%d, count:%d",
				start, before, count);
		final Spannable spannable = (Spannable) s;
		for (int i = 0; i < count; i++) {
			if (hasGifSpan(spannable, start + i)) continue;
			final GifSpan span = new GifSpan(editText, R.drawable.break_droid, 1);
			span.start();
			spannable.setSpan(span, start + i, start + i + 1,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
	}

	private boolean hasGifSpan(final Spannable s, final int position) {
		final GifSpan[] gifSpans = s.getSpans(position, position + 1, GifSpan.class);
		if (gifSpans != null) {
			for (GifSpan gifSpan : gifSpans) {
				final int spanStart = s.getSpanStart(gifSpan);
				final int spanEnd = s.getSpanEnd(gifSpan);
				if (spanStart + 1 != spanEnd) {
					s.removeSpan(gifSpan);
					return false;
				} else if (spanStart == position) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void afterTextChanged(Editable s) {

	}
}