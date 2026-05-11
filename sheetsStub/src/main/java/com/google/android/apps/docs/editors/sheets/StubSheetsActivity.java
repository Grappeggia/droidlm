package com.google.android.apps.docs.editors.sheets;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class StubSheetsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        Uri data = intent != null ? intent.getData() : null;
        String dataText = data != null ? data.toString() : "No file opened";

        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Google Sheets");
        title.setTextSize(32);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("DroidLM E2E workspace viewer");
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);

        TextView uriView = new TextView(this);
        uriView.setText(dataText);
        uriView.setTextSize(14);
        uriView.setGravity(Gravity.CENTER);

        layout.addView(title);
        layout.addView(subtitle);
        layout.addView(uriView);
        setContentView(layout);
    }
}
