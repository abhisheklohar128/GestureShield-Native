package com.gestureshield.app;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebSettings;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        WebView webView = new WebView(this);
        setContentView(webView);
        
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setCamera(true);
        
        // तुमची GitHub Pages URL टाका (तुम्ही आधी बनवली होती)
        webView.loadUrl("https://abhisheklohar128.github.io/GestureShield/");
    }
}
