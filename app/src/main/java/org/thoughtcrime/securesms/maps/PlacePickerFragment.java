package org.thoughtcrime.securesms.maps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.unfacd.android.R;

import org.signal.core.util.logging.Log;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

/**
 * Allows selection of an address from a google map.
 * <p>
 * Based on https://github.com/suchoX/PlacePicker
 */
public final class PlacePickerFragment extends Fragment {

  private static final String TAG = Log.tag(PlacePickerActivity.class);

  // If it cannot load location for any reason, it defaults to the prime meridian.
  private static final LatLng PRIME_MERIDIAN = new LatLng(51.4779, -0.0015);
  private static final String ADDRESS_INTENT = "ADDRESS"; //AA result return intent for activity result (orig implementation)
  private static final float  ZOOM           = 12.0f;//17.0f;

  //AA+
  public static final String SELECTION_MODE_INTENT  = "SELECTION_MODE";
  public static final String SEED_LOCATION_INTENT   = "SEED_LOCATION";
  public static final String LOCATION_PICKED_RESULT = "LOCATION_PICKED_RESULT";
  public static final String LOCATION_PICKED_RESULT_KEY = "LOCATION_PICKED_RESULT_KEY";
  private LatLng seededLocation = PRIME_MERIDIAN;
  boolean isHomebaseGeolocPicker = false;
  //

  private static final int                   ANIMATION_DURATION     = 250;
  private static final OvershootInterpolator OVERSHOOT_INTERPOLATOR = new OvershootInterpolator();

  private SingleAddressBottomSheet bottomSheet;
  private Address                  currentAddress;
  private LatLng                   initialLocation;
  private LatLng                   currentLocation = new LatLng(0, 0);
  private AddressLookup            addressLookup;
  private GoogleMap                googleMap;

  public static void startActivityForResultAtCurrentLocation(@NonNull Fragment fragment, int requestCode) {
    fragment.startActivityForResult(new Intent(fragment.requireActivity(), PlacePickerActivity.class), requestCode);
  }

  public static AddressData addressFromData(@NonNull Intent data) {
    return data.getParcelableExtra(ADDRESS_INTENT);
  }

  @Override
  public @NonNull View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.activity_place_picker, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    //AA+
    Location seedLocation;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      seedLocation = getArguments().getParcelable(SEED_LOCATION_INTENT, Location.class);
    } else seedLocation = getArguments().getParcelable(SEED_LOCATION_INTENT);

    if (seedLocation != null) seededLocation = new LatLng(seedLocation.getLatitude(), seedLocation.getLongitude());
    isHomebaseGeolocPicker = getArguments().getBoolean(SELECTION_MODE_INTENT);
    //

    bottomSheet      = view.findViewById(R.id.bottom_sheet);
    View markerImage = view.findViewById(R.id.marker_image_view);
    View fab         = view.findViewById(R.id.place_chosen_button);

    fab.setOnClickListener(v -> finishWithAddress());

    FusedLocationProviderClient fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

    //AA+ override with seeded location if provided
    if (seededLocation != PRIME_MERIDIAN) {
      setInitialLocation(seededLocation);
    } else {//
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
              requireActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
              requireActivity().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
      ) {
        fusedLocationClient.getLastLocation()
                           .addOnFailureListener(e -> {
                             Log.e(TAG, "Failed to get location", e);
                             setInitialLocation(PRIME_MERIDIAN);
                           })
                           .addOnSuccessListener(location -> {
                             if (location == null) {
                               Log.w(TAG, "Failed to get location");
                               setInitialLocation(PRIME_MERIDIAN);
                             }
                             else {
                               setInitialLocation(new LatLng(location.getLatitude(), location.getLongitude()));
                             }
                           });
      }
      else {
        Log.w(TAG, "No location permissions");
        setInitialLocation(PRIME_MERIDIAN);
      }
    }

    SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
    if (mapFragment == null) throw new AssertionError("No map fragment");

    mapFragment.getMapAsync(googleMap -> {

      setMap(googleMap);

      enableMyLocationButtonIfHaveThePermission(googleMap);

      googleMap.setOnCameraMoveStartedListener(i -> {
        markerImage.animate()
                   .translationY(-75f)
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        bottomSheet.hide();
      });

      googleMap.setOnCameraIdleListener(() -> {
        markerImage.animate()
                   .translationY(0f)
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        setCurrentLocation(googleMap.getCameraPosition().target);
      });
    });
  }

  //AA+ fragment conversion
  public boolean dispatchTouchEvent(MotionEvent ev) {
    return true;
  }

 void onNewIntent(Intent intent) {
  Log.i(TAG, "onNewIntent()");

  if (requireActivity().isFinishing()) {
    Log.w(TAG, "Activity is finishing...");
    return;
  }

  requireActivity().setIntent(intent);

}

  private void setInitialLocation(@NonNull LatLng latLng) {
    initialLocation = latLng;

    moveMapToInitialIfPossible();
  }

  private void setMap(GoogleMap googleMap) {
    this.googleMap = googleMap;

    moveMapToInitialIfPossible();
  }

  private void moveMapToInitialIfPossible() {
    if (initialLocation != null && googleMap != null) {
      Log.d(TAG, "Moving map to initial location");
      googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialLocation, ZOOM));
      setCurrentLocation(initialLocation);
    }
  }

  private void setCurrentLocation(LatLng location) {
    currentLocation = location;
    bottomSheet.showLoading();
    lookupAddress(location);
  }

  private void finishWithAddress() {
    String      address      = currentAddress != null && currentAddress.getAddressLine(0) != null ? currentAddress.getAddressLine(0) : "";
    AddressData addressData  = new AddressData(currentLocation.latitude, currentLocation.longitude, address);

    Bundle resultBundle = new Bundle();
    resultBundle.putParcelable(LOCATION_PICKED_RESULT, currentAddress);
    getParentFragmentManager().setFragmentResult(LOCATION_PICKED_RESULT_KEY, resultBundle);
    NavHostFragment.findNavController(this).popBackStack();
  }

  private void enableMyLocationButtonIfHaveThePermission(GoogleMap googleMap) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            requireActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED ||
            requireActivity().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    {
      googleMap.setMyLocationEnabled(true);
    }
  }

  private void lookupAddress(@Nullable LatLng target) {
    if (addressLookup != null) {
      addressLookup.cancel(true);
    }
    addressLookup = new AddressLookup();
    addressLookup.execute(target);
  }

  @Override
  public void onPause() {
    super.onPause();
    if (addressLookup != null) {
      addressLookup.cancel(true);
    }
  }

  @SuppressLint("StaticFieldLeak")
  private class AddressLookup extends AsyncTask<LatLng, Void, Address> {

    private final String TAG = Log.tag(AddressLookup.class);
    private final Geocoder geocoder;

    AddressLookup() {
      geocoder = new Geocoder(requireActivity().getApplicationContext(), Locale.getDefault());
    }

    @Override
    protected Address doInBackground(LatLng... latLngs) {
      if (latLngs.length == 0) return null;
      LatLng latLng = latLngs[0];
      if (latLng == null) return null;
      try {
        List<Address> result = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1);
        return !result.isEmpty() ? result.get(0) : null;
      } catch (IOException e) {
        Log.e(TAG, "Failed to get address from location", e);
        return null;
      }
    }

    @Override
    protected void onPostExecute(@Nullable Address address) {
      Log.d(TAG, String.format("%s", addressToString(address)));
      currentAddress = address;
      if (address != null) {
        if (isHomebaseGeolocPicker) {//AA+
          bottomSheet.showResult(address.getLatitude(), address.getLongitude(), addressToShortString(address));
        } else {
          bottomSheet.showResult(address.getLatitude(), address.getLongitude(), addressToShortString(address), addressToString(address));
        }
      } else {
        bottomSheet.hide();
      }
    }
  }

  private static @NonNull String addressToString(@Nullable Address address) {
    return address != null ? address.getAddressLine(0) : "";
  }

  private static @NonNull String addressToShortString(@Nullable Address address) {
    if (address == null) return "";

    String   addressLine = address.getAddressLine(0);
    String[] split       = addressLine.split(",");

    if (split.length >= 3) {
      return split[1].trim() + ", " + split[2].trim();
    } else if (split.length == 2) {
      return split[1].trim();
    } else return split[0].trim();
  }
}