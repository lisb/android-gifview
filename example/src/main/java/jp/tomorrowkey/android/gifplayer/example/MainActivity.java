package jp.tomorrowkey.android.gifplayer.example;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import jp.tomorrowkey.android.gifplayer.GifSpan;
import jp.tomorrowkey.android.gifplayer.GifView;
import timber.log.Timber;

public class MainActivity extends Activity implements OnClickListener {

	private GifView gifView;

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
		final EditText editText = findViewById(R.id.editText);

		gifView.setGif(R.drawable.break_droid);
		btnPlay.setOnClickListener(this);
		btnPause.setOnClickListener(this);
		btnStop.setOnClickListener(this);
		btnPrevFrame.setOnClickListener(this);
		btnNextFrame.setOnClickListener(this);

		final SpannableStringBuilder sb = new SpannableStringBuilder("A");
		final GifSpan span = new GifSpan(editText, R.drawable.break_droid, 10);
		span.start();
		sb.setSpan(span, 0, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		editText.setText(sb, TextView.BufferType.EDITABLE);
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
}