package com.google.android.apps.docs.editors.docs;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class StubDocsActivity extends Activity {
    private Uri backingUri;
    private EditText documentEditor;
    private boolean suppressWrites = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        backingUri = intent != null ? intent.getData() : null;
        String documentText = readDocument(backingUri);
        String documentTitle = extractTitle(documentText);
        List<String> sectionHeadings = extractSectionHeadings(documentText);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 96);
        scrollView.addView(
            layout,
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        );

        TextView title = new TextView(this);
        title.setText(documentTitle);
        title.setTextSize(28);
        layout.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText(backingUri != null ? backingUri.toString() : "No file opened");
        subtitle.setTextSize(12);
        layout.addView(subtitle, matchWrap());

        for (String heading : sectionHeadings) {
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(heading);
            button.setOnClickListener(ignored -> moveCursorToHeading(heading));
            layout.addView(button, matchWrap());
        }

        documentEditor = new EditText(this);
        documentEditor.setText(documentText);
        documentEditor.setTextSize(16);
        documentEditor.setSingleLine(false);
        documentEditor.setHorizontallyScrolling(false);
        documentEditor.setMinLines(24);
        documentEditor.setInputType(
            InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        documentEditor.setFocusable(true);
        documentEditor.setFocusableInTouchMode(true);
        layout.addView(documentEditor, matchWrap());

        documentEditor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                persistDocument();
            }
        });

        setContentView(scrollView);
        documentEditor.post(() -> {
            documentEditor.requestFocus();
            documentEditor.setSelection(0);
        });
    }

    private void moveCursorToHeading(String heading) {
        if (documentEditor == null) return;
        String text = documentEditor.getText().toString();
        int index = text.indexOf("\n" + heading + "\n");
        if (index < 0 && text.startsWith(heading + "\n")) {
            index = 0;
        } else if (index >= 0) {
            index += 1;
        }
        documentEditor.requestFocus();
        int selection = Math.max(0, index);
        documentEditor.setSelection(selection, selection);
        documentEditor.bringPointIntoView(selection);
    }

    private ViewGroup.LayoutParams matchWrap() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void persistDocument() {
        if (suppressWrites || backingUri == null || documentEditor == null) return;
    }

    private String readDocument(Uri uri) {
        if (uri == null) return "Google Docs\n\nOverview\nNo file opened";
        try (InputStream stream = openForRead(uri)) {
            if (stream == null) return "Google Docs\n\nOverview\nNo file opened";
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) text.append('\n');
                text.append(line);
                first = false;
            }
            return text.length() == 0 ? "Google Docs\n\nOverview\nEmpty file" : text.toString();
        } catch (IOException ignored) {
            return "Google Docs\n\nOverview\nCould not read file";
        }
    }

    private void writeDocument(Uri uri, String text) {
        try (OutputStream stream = openForWrite(uri)) {
            if (stream == null) return;
            OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
            writer.write(text);
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    private InputStream openForRead(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return null;
            File file = new File(path);
            return file.isFile() ? new FileInputStream(file) : null;
        }
        return getContentResolver().openInputStream(uri);
    }

    private OutputStream openForWrite(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return null;
            return new FileOutputStream(new File(path), false);
        }
        return getContentResolver().openOutputStream(uri, "wt");
    }

    private static String extractTitle(String text) {
        if (text == null || text.trim().isEmpty()) return "Google Docs";
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        return lines.length > 0 && !lines[0].trim().isEmpty() ? lines[0].trim() : "Google Docs";
    }

    private static List<String> extractSectionHeadings(String text) {
        String[] lines = text == null ? new String[0] : text.replace("\r\n", "\n").split("\n", -1);
        LinkedHashSet<String> headings = new LinkedHashSet<>();
        for (int index = 1; index < lines.length; index++) {
            String previous = lines[index - 1].trim();
            String current = lines[index].trim();
            String next = index + 1 < lines.length ? lines[index + 1].trim() : "";
            if (!previous.isEmpty()) continue;
            if (current.isEmpty() || next.isEmpty()) continue;
            if (current.length() > 48 || current.contains(":")) continue;
            headings.add(current);
        }
        if (headings.isEmpty()) {
            headings.add("Overview");
        }
        return new ArrayList<>(headings);
    }
}
