package net.pocketnai.ui.chat;

import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 测试 APK 的独立进程没有被测应用的 Kotlin 库；使用纯 Android Java 创建真实 IME 窗口。 */
public class ChatTestImeService extends InputMethodService {
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public boolean onEvaluateInputViewShown() { return true; }
    @Override public boolean onShowInputRequested(int flags, boolean configChange) { return true; }
    @Override public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(45, 48, 55));
        root.setLayoutParams(new LinearLayout.LayoutParams(-1, (int) (300 * getResources().getDisplayMetrics().density)));
        TextView title = new TextView(this);
        title.setText("Android 中文组合输入验收"); title.setTextSize(18); title.setTextColor(Color.WHITE); title.setPadding(20, 20, 20, 20);
        root.addView(title);
        LinearLayout row = new LinearLayout(this);
        addKey(row, "拼音组合", () -> { getCurrentInputConnection().setComposingText("xiang", 1); getCurrentInputConnection().setComposingText("xiangyao", 1); });
        addKey(row, "想要", () -> getCurrentInputConnection().commitText("想要", 1));
        addKey(row, "生成", () -> getCurrentInputConnection().commitText("生成", 1));
        root.addView(row);
        LinearLayout second = new LinearLayout(this);
        addKey(second, "咖啡店", () -> getCurrentInputConnection().commitText("雨后的咖啡店", 1));
        addKey(second, "换行", () -> getCurrentInputConnection().commitText("\n", 1));
        addKey(second, "删除", () -> getCurrentInputConnection().deleteSurroundingText(1, 0));
        root.addView(second);
        return root;
    }
    private void addKey(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, 150, 1));
    }
}
