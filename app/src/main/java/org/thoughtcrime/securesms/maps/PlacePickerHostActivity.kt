package org.thoughtcrime.securesms.maps

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.unfacd.android.R
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import org.thoughtcrime.securesms.util.DynamicTheme

open class PlacePickerHostActivity : AppCompatActivity() {

  private lateinit var fragment: PlacePickerFragment

  private val dynamicTheme: DynamicTheme = DynamicNoActionBarTheme()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    dynamicTheme.onCreate(this)
    setContentView(R.layout.place_picker_fragment_container)

    fragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as PlacePickerFragment
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    fragment.onNewIntent(intent)
  }

  override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
    return fragment.dispatchTouchEvent(ev) || super.dispatchTouchEvent(ev)
  }

  override fun onResume() {
    super.onResume()
    dynamicTheme.onResume(this)
  }
}