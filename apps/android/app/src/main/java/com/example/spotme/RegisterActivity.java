package com.example.spotme;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class RegisterActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        TextInputEditText nameInput = findViewById(R.id.nameInput);
        TextInputEditText emailInput = findViewById(R.id.emailInput);
        TextInputEditText passwordInput = findViewById(R.id.passwordInput);
        MaterialButton registerButton = findViewById(R.id.registerButton);
        TextView loginText = findViewById(R.id.loginText);

        registerButton.setOnClickListener(v -> {
            // Handle registration logic
        });

        loginText.setOnClickListener(v -> {
            finish();
        });
    }
}
