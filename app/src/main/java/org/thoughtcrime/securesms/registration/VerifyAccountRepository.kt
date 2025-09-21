package org.thoughtcrime.securesms.registration

import android.app.Application
import android.os.Handler
import com.unfacd.android.jobs.RegistrationStatusVerifierJob
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.gcm.FcmUtil
import org.thoughtcrime.securesms.pin.KbsRepository
import org.thoughtcrime.securesms.pin.KeyBackupSystemWrongPinException
import org.thoughtcrime.securesms.pin.TokenData
import org.thoughtcrime.securesms.push.AccountManagerFactory
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.KbsPinData
import org.whispersystems.signalservice.api.KeyBackupSystemNoDataException
import org.whispersystems.signalservice.api.SignalServiceAccountManager
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.ServiceResponse
import org.whispersystems.signalservice.internal.push.RequestVerificationCodeResponse
import org.whispersystems.signalservice.internal.push.VerifyAccountResponse
import java.io.IOException
import java.util.Locale
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Request SMS/Phone verification codes to help prove ownership of a phone number.
 * AA this currently sends an email based verification message with coded embedded
 */
class VerifyAccountRepository(private val context: Application) {

  fun requestVerificationCode(
    e164: String,
    password: String,
    mode: Mode,
    captchaToken: String? = null,
    email: String //AA+ email
  ): Single<ServiceResponse<RequestVerificationCodeResponse>> {
    Log.d(TAG, "EMAIL Verification requested")

    return Single.fromCallable {
      val fcmToken: Optional<String> = FcmUtil.getToken()
      val accountManager = AccountManagerFactory.createUnauthenticated(context, email, SignalServiceAddress.DEFAULT_DEVICE_ID, password)//AA+ email
      val pushChallenge = PushChallengeRequest.getPushChallengeBlocking(accountManager, fcmToken, e164, PUSH_REQUEST_TIMEOUT, email)//AA+ for ufsrv return rego email

      if (mode == Mode.PHONE_CALL) {
        accountManager.requestVoiceVerificationCode(Locale.getDefault(), Optional.ofNullable(captchaToken), pushChallenge, fcmToken)
      } else {
        accountManager.requestSmsVerificationCode(mode.isSmsRetrieverSupported, Optional.ofNullable(captchaToken), pushChallenge, fcmToken, e164, email)//AA+ last 3 params
      }
    }.subscribeOn(Schedulers.io())
  }

/*  fun requestVerificationCode(
    e164: String,
    password: String,
    mode: Mode,
    captchaToken: String? = null
  ): Single<ServiceResponse<RequestVerificationCodeResponse>> {
    Log.d(TAG, "SMS Verification requested")

//    return Single.error(Throwable())//AA+ as temporary fix to compile
    //AA+ todo fixup https://github.com/signalapp/Signal-Android/commit/8e32592218cac74c930319b44edb2c93229f0268#diff-ec8d60c9374a876c568d6a4a11e31159c2f360e834a370284997e0820beaacf7
    return Single.fromCallable {
      val fcmToken: Optional<String> = FcmUtil.getToken()
      val accountManager = AccountManagerFactory.createUnauthenticated(context, e164, SignalServiceAddress.DEFAULT_DEVICE_ID, password)
      val pushChallenge = PushChallengeRequest.getPushChallengeBlocking(accountManager, fcmToken, e164, PUSH_REQUEST_TIMEOUT)

      if (mode == Mode.PHONE_CALL) {
        accountManager.requestVoiceVerificationCode(Locale.getDefault(), Optional.ofNullable(captchaToken), pushChallenge, fcmToken)
      } else {
        accountManager.requestSmsVerificationCode(mode.isSmsRetrieverSupported, Optional.ofNullable(captchaToken), pushChallenge, fcmToken)
      }
    }.subscribeOn(Schedulers.io())
  }*/

  //AA- orig
 /* fun verifyAccount(registrationData: RegistrationData): Single<ServiceResponse<VerifyAccountResponse>> {
    val universalUnidentifiedAccess: Boolean = TextSecurePreferences.isUniversalUnidentifiedAccess(context)
    val unidentifiedAccessKey: ByteArray = UnidentifiedAccess.deriveAccessKeyFrom(registrationData.profileKey)

    val accountManager: SignalServiceAccountManager = AccountManagerFactory.createUnauthenticated(
      context,
      registrationData.e164,
      SignalServiceAddress.DEFAULT_DEVICE_ID,
      registrationData.password
    )

    return Single.fromCallable {
      accountManager.verifyAccount(
        registrationData.code,
        registrationData.registrationId,
        registrationData.isNotFcm,
        unidentifiedAccessKey,
        universalUnidentifiedAccess,
        AppCapabilities.getCapabilities(true),
        SignalStore.phoneNumberPrivacy().phoneNumberListingMode.isDiscoverable
      )
    }.subscribeOn(Schedulers.io())
  }*/

  fun verifyAccount(registrationData: RegistrationData): Single<ServiceResponse<VerifyAccountResponse>> {
    val universalUnidentifiedAccess: Boolean = TextSecurePreferences.isUniversalUnidentifiedAccess(context)
    val unidentifiedAccessKey: ByteArray = UnidentifiedAccess.deriveAccessKeyFrom(registrationData.profileKey)

    val accountManager: SignalServiceAccountManager = AccountManagerFactory.createUnauthenticated(
      context,
      registrationData.email,//AA+ use email for authorisation until ufsrvuid is returned further downstream
      SignalServiceAddress.DEFAULT_DEVICE_ID,
      registrationData.password
    )

    return Single.fromCallable {
      accountManager.verifyAccount(//AA+ calls /V1/Account/VerifyNew
        registrationData.code,
        null,
        registrationData.registrationId,
        registrationData.isNotFcm,
        "",
        "",
        unidentifiedAccessKey,
        universalUnidentifiedAccess,
        registrationData.regoCookie,
        registrationData.email,
        registrationData.e164,
        registrationData.profileKey.serialize()
      );
    }.subscribeOn(Schedulers.io())
  }


  fun verifyAccountWithPin(registrationData: RegistrationData, pin: String, tokenData: TokenData): Single<ServiceResponse<VerifyAccountWithRegistrationLockResponse>> {
    val universalUnidentifiedAccess: Boolean = TextSecurePreferences.isUniversalUnidentifiedAccess(context)
    val unidentifiedAccessKey: ByteArray = UnidentifiedAccess.deriveAccessKeyFrom(registrationData.profileKey)

    val accountManager: SignalServiceAccountManager = AccountManagerFactory.createUnauthenticated(
      context,
      registrationData.email,//AA+
      SignalServiceAddress.DEFAULT_DEVICE_ID,
      registrationData.password
    )

    return Single.fromCallable {
      try {
        val kbsData: KbsPinData = KbsRepository.restoreMasterKey(
          pin,
          tokenData.enclave,
          tokenData.basicAuth,
          tokenData.tokenResponse,
          Optional.of(accountManager)
        )!!
        val registrationLockV2: String = kbsData.masterKey.deriveRegistrationLock()

        val response: ServiceResponse<VerifyAccountResponse> = accountManager.verifyAccount(//AA+ calls /V1/Account/VerifyNew
          registrationData.code,
          null,
          registrationData.registrationId,
          registrationData.isNotFcm,
          "",
          registrationLockV2,
          unidentifiedAccessKey,
          universalUnidentifiedAccess,
          registrationData.regoCookie,
          registrationData.email,
          registrationData.e164,
          registrationData.profileKey.serialize()
        )

       /* val response: ServiceResponse<VerifyAccountResponse> = accountManager.verifyAccountWithRegistrationLockPin(
          registrationData.code,
          registrationData.registrationId,
          registrationData.isNotFcm,
          registrationLockV2,
          unidentifiedAccessKey,
          universalUnidentifiedAccess,
          AppCapabilities.getCapabilities(true),
          SignalStore.phoneNumberPrivacy().phoneNumberListingMode.isDiscoverable
        )*///AA-
        VerifyAccountWithRegistrationLockResponse.from(response, kbsData)
      } catch (e: KeyBackupSystemWrongPinException) {
        ServiceResponse.forExecutionError(e)
      } catch (e: KeyBackupSystemNoDataException) {
        ServiceResponse.forExecutionError(e)
      } catch (e: IOException) {
        ServiceResponse.forExecutionError(e)
      }
    }.subscribeOn(Schedulers.io())
  }

  data class EmailVerifiedListener(val handler: Handler) {
    fun prepare(): Runnable {
      val runnableCode: Runnable = object : Runnable {
        override fun run() {
          ApplicationDependencies.getJobManager().add(RegistrationStatusVerifierJob())

          handler.postDelayed(this, TimeUnit.SECONDS.toMillis(15))
        }
      }

      return runnableCode
    }

    fun launch(runnable: Runnable) {
      handler.post(runnable)
    }

    fun removeEmailVerifiedListener(runnable: Runnable) {
      handler.removeCallbacks(runnable)
    }
  }
  //

  enum class Mode(val isSmsRetrieverSupported: Boolean) {
    SMS_WITH_LISTENER(true),
    SMS_WITHOUT_LISTENER(false),
    PHONE_CALL(false),
    WEB_LINK(false);//AA+
  }

  companion object {
    private val TAG = Log.tag(VerifyAccountRepository::class.java)
    private val PUSH_REQUEST_TIMEOUT = TimeUnit.SECONDS.toMillis(5)
  }

  data class VerifyAccountWithRegistrationLockResponse(val verifyAccountResponse: VerifyAccountResponse, val kbsData: KbsPinData) {
    companion object {
      fun from(response: ServiceResponse<VerifyAccountResponse>, kbsData: KbsPinData): ServiceResponse<VerifyAccountWithRegistrationLockResponse> {
        return if (response.result.isPresent) {
          ServiceResponse.forResult(VerifyAccountWithRegistrationLockResponse(response.result.get(), kbsData), 200, null)
        } else {
          ServiceResponse.coerceError(response)
        }
      }
    }
  }
}