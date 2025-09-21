package com.unfacd.android.integrity;

import android.util.Base64;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.IntegrityTokenResponse;
import com.google.android.play.core.integrity.StandardIntegrityException;
import com.google.android.play.core.integrity.StandardIntegrityManager;
import com.unfacd.android.ApplicationContext;

import org.signal.core.util.logging.Log;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static com.google.android.play.core.integrity.model.StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID;

public class IntegrityUtils
{
  private static final String TAG = Log.tag(IntegrityUtils.class);

  private static final Long GC_PROJECT_NUMBER = 613717818841L; //as per google cloud project settings
  final static private StandardIntegrityManager standardIntegrityManager = IntegrityManagerFactory.createStandard(ApplicationContext.getInstance());
  final static private StandardIntegrityManager.PrepareIntegrityTokenRequest tokenRequest = StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(GC_PROJECT_NUMBER).build();

  static private StandardIntegrityManager.StandardIntegrityTokenProvider integrityTokenProvider;


  synchronized static public Task<StandardIntegrityManager. StandardIntegrityTokenProvider> initialiseIntegrityApi()
  {
// Prepare integrity token. Can be called once in a while to keep internal state fresh
    return standardIntegrityManager.prepareIntegrityToken(tokenRequest)
                                   .addOnSuccessListener(tokenProvider -> integrityTokenProvider = tokenProvider)
                                   .addOnFailureListener(exception -> Log.e(TAG, exception));
  }

  static public void requestIntegrityVerdict(String requestHash, Consumer<Optional<String>> tokenConsumer)
  {
    Task<StandardIntegrityManager.StandardIntegrityToken> integrityTokenResponse = integrityTokenProvider.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build());
    integrityTokenResponse.addOnSuccessListener(response -> tokenConsumer.accept(Optional.of(response.token())))
                          .addOnFailureListener(exception -> tokenConsumer.accept(Optional.empty()));
  }

  static public Task<StandardIntegrityManager.StandardIntegrityToken> provideIntegrityTokenRequestTask(String requestHash)
  {
    return integrityTokenProvider.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build());
  }

  static public void requestIntegrityToken(String encodedRequestHash, Consumer<Optional<String>> tokenConsumer) throws StandardIntegrityException
  {
    try {
      StandardIntegrityManager.StandardIntegrityToken tokenResponse = Tasks.await(IntegrityUtils.provideIntegrityTokenRequestTask(encodedRequestHash));
      tokenConsumer.accept(Optional.of(tokenResponse.token()));
    } catch (ExecutionException | InterruptedException x) {
      if (x instanceof StandardIntegrityException) {
        switch (((StandardIntegrityException)x).getErrorCode()) {
          case INTEGRITY_TOKEN_PROVIDER_INVALID:
            throw ((StandardIntegrityException)x);
        }
      }
      Log.e(TAG, x.getMessage());
    }
  }

  static public void requestIntegrityVerdictAsync(String requestHash, Consumer<Optional<String>> tokenConsumer)
  {
    String nonce = Base64.encodeToString(requestHash.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);
    IntegrityManager integrityManager = IntegrityManagerFactory.create(ApplicationContext.getInstance());
    Task<IntegrityTokenResponse> integrityTokenResponse = integrityManager.requestIntegrityToken(IntegrityTokenRequest.builder().setNonce(nonce)
                                                                                                                      .setCloudProjectNumber(GC_PROJECT_NUMBER)
                                                                                                                      .build());
    integrityTokenResponse.addOnCompleteListener(task -> {if (task.isSuccessful()) tokenConsumer.accept(Optional.of(task.getResult().token()));});

    integrityTokenResponse.addOnFailureListener(e -> {tokenConsumer.accept(Optional.empty());});

  }
}
