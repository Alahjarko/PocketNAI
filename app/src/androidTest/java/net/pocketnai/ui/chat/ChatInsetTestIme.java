package net.pocketnai.ui.chat;

import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Test APK only: a real Android IME window for reproducible inset animation checks. */
public class ChatInsetTestIme extends InputMethodService {
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public boolean onEvaluateInputViewShown() { return true; }
    @Override public boolean onShowInputRequested(int flags, boolean configChange) { return true; }
    @Override public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.rgb(45, 48, 55));
        root.setMinimumHeight((int) (280 * getResources().getDisplayMetrics().density));
        root.setLayoutParams(new LinearLayout.LayoutParams(-1, (int) (280 * getResources().getDisplayMetrics().density)));
        TextView title = new TextView(this);
        title.setText("键盘窗口动画验收 · 点击收起");
        title.setTextSize(20); title.setTextColor(Color.WHITE); title.setPadding(24, 24, 24, 24);
        title.setOnClickListener(view -> requestHideSelf(0));
        root.addView(title);
        return root;
    }
}
