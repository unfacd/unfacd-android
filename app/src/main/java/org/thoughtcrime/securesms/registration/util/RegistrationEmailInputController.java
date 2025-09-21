package org.thoughtcrime.securesms.registration.util;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.thoughtcrime.securesms.components.LabeledEditText;
import org.thoughtcrime.securesms.registration.viewmodel.EmailViewState;

import androidx.annotation.NonNull;

/**
 * Handle the logic and formatting of phone number input for registration/change number flows.
 */
public final class RegistrationEmailInputController {

  private final Context                          context;
  private final LabeledEditText                  email;
  private final boolean                          lastInput;
  private final CallbacksForUfRegistration       callbacks;

  private boolean            isUpdating = true;

  public RegistrationEmailInputController(@NonNull Context context,
                                           @NonNull LabeledEditText email,
                                           boolean lastInput,
                                           @NonNull CallbacksForUfRegistration callbacks)
  {
    this.context     = context;
    this.email       = email;
    this.lastInput   = lastInput;
    this.callbacks   = callbacks;

    setUpEmailInput();
  }

  private void setUpEmailInput() {
    EditText emailInput = email.getInput();

    emailInput.addTextChangedListener(new EmailChangedListener());

    email.setOnFocusChangeListener((v, hasFocus) -> {
      if (hasFocus) {
        callbacks.onEmailFocused();
      }
    });

    emailInput.setImeOptions(lastInput ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
    emailInput.setOnEditorActionListener((v, actionId, event) -> {
      if (actionId == EditorInfo.IME_ACTION_NEXT) {
        callbacks.onEmailInputNext(v);
        return true;
      } else if (actionId == EditorInfo.IME_ACTION_DONE) {
        callbacks.onEmailInputDone(v);
        return true;
      }
      return false;
    });
  }


  public void updateEmail (@NonNull EmailViewState emailViewState) {
    String email = emailViewState.getEmail();

    isUpdating = true;

    if (!this.email.getText().toString().equals(email) && !TextUtils.isEmpty(email)) {
      this.email.setText(email);
    }

    isUpdating = false;
  }

  private String reformatText(Editable s) {
    if (TextUtils.isEmpty(s)) {
      return null;
    }

    return String.valueOf(s);

  }

  private class EmailChangedListener implements TextWatcher {

    @Override
    public void afterTextChanged(Editable s) {
      String number = reformatText(s);

      if (number == null) return;

      if (!isUpdating) {
        callbacks.setEmail(number);
      }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }
  }

  public interface CallbacksForUfRegistration {
    void onEmailFocused();

    void onEmailInputNext(@NonNull View view);

    void onEmailInputDone(@NonNull View view);

    void setEmail(@NonNull String email);
  }
}