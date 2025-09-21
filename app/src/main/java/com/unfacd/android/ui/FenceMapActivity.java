/**
 * Copyright (C) 2015-2025 unfacd works
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.unfacd.android.ui;

import android.Manifest;
import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.arlib.floatingsearchview.FloatingSearchView;
import com.arlib.floatingsearchview.suggestions.SearchSuggestionsAdapter;
import com.arlib.floatingsearchview.suggestions.model.SearchSuggestion;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.maps.android.SphericalUtil;
import com.google.maps.android.clustering.Cluster;
import com.google.maps.android.clustering.ClusterItem;
import com.google.maps.android.clustering.ClusterManager;
import com.google.maps.android.clustering.view.DefaultClusterRenderer;
import com.google.maps.android.ui.IconGenerator;
import com.google.protobuf.InvalidProtocolBufferException;
import com.mahc.custombottomsheetbehavior.BottomSheetBehaviorGoogleMapsLike;
import com.mahc.custombottomsheetbehavior.MergedAppBarLayout;
import com.mahc.custombottomsheetbehavior.MergedAppBarLayoutBehavior;
import com.unfacd.android.R;
import com.unfacd.android.location.UfsrvLocationUtils;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.mapable.MapableGroup;
import com.unfacd.android.mapable.MapableThing;
import com.unfacd.android.mapable.MapableThingProvider;
import com.unfacd.android.ui.components.CollapsingFenceMap.ItemPagerAdapter;
import com.unfacd.android.ui.components.CollapsingFenceMap.search.FenceSearchResultSuggestion;
import com.unfacd.android.ui.components.MultiDrawable;
import com.unfacd.android.utils.FencesNearByReceiver;
import com.unfacd.android.utils.IntentServiceGetFencesNearBy;
import com.unfacd.android.utils.IntentServiceReceiver;
import com.unfacd.android.utils.IntentServiceSearchFences;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewpager.widget.ViewPager;


public class FenceMapActivity extends     AppCompatActivity
                              implements  ClusterManager.OnClusterClickListener<MapableThing>,
                                          ClusterManager.OnClusterInfoWindowClickListener<MapableThing>,
                                          ClusterManager.OnClusterItemClickListener<MapableThing>,
                                          ClusterManager.OnClusterItemInfoWindowClickListener<MapableThing>,
                                          AppBarLayout.OnOffsetChangedListener,
                                          MapableThing.MapableThingModifiedListener

{
  private static final String TAG = Log.tag(FenceMapActivity.class);

  private FloatingActionButton fab;
  private FencesNearByReceiver fencesNearByReceiver;
  private IntentServiceReceiver fencesSearchReceiver;
  private SupportMapFragment mMapFragment;
  private GoogleMap mMap;
  private ClusterManager<MapableThing> mClusterManager;
  private LatLng  mPosition;
  private float mZoom;
  private CameraPosition cameraPosition;
  private CountDownTimer mDragTimer;
  IdleCameraMultiListener ml = new IdleCameraMultiListener();

  private boolean mTimerIsRunning = false;
  private long DRAG_TIMER_INTERVAL = 100;//millis

  private BottomSheetDisplayModel displayModel;
  private int nearbyGroupsCount = 0;
  private GroupDatabase.GroupRecord clickedGroupRecord = null;

  ViewPager viewPager;
  MergedAppBarLayoutBehavior mergedAppBarLayoutBehavior;
  TextView bottomSheetMainhead;
  TextView bottomSheetSubhead1;
  TextView bottomSheetSubhead2;
  private AppBarLayout mAppBar;
  private FloatingSearchView mSearchView;

  int[] mDrawables = {
        R.drawable.cheese_3,
//        R.drawable.cheese_3,
  };

  private ArrayList<Drawable> drawables = new ArrayList<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.uf_fence_map_activity);

    askForPermissionIfNeededAndLaunch();
    if (UfsrvLocationUtils.isLocationPermissionsGranted(this)) {
      initialiseMapAndResources();
    }
  }

  private void initialiseMapAndResources() {
    initialiseResources();
    initialiseSearchResources();

    setupFencesNearByServiceReceiver();
    setupFencesSearchServiceReceiver();
  }

  @Override
  protected void onStop () {
    super.onStop();

  }

    //https://guides.codepath.com/android/Extended-ActionBar-Guide
  @Override
  public boolean onCreateOptionsMenu( Menu menu) {
//    getMenuInflater().inflate( R.menu.uf_toolbar_search, menu);
//
//    MenuItem myActionMenuItem = menu.findItem( R.id.action_search);
//    final SearchView searchView = (SearchView) myActionMenuItem.getActionView();
//
//    //these enable exploded view of action search. Not desirable at this stage
//    //myActionMenuItem.expandActionView();
//    //searchView.requestFocus();
//
//    searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
//      @Override
//      public boolean onQueryTextSubmit(String query) {
//        searchView.clearFocus();
//
//        return true;//false;
//      }
//
//      @Override
//      public boolean onQueryTextChange(final String newText) {
//        if (TextUtils.isEmpty(newText)) {
////          adapter.filter("");
////          listView.clearTextFilter();
//        } else {
////          adapter.filter(newText);
//          Timer timer = new Timer();
//          timer.schedule(new TimerTask() {
//            @Override
//            public void run() {
//              Intent intent = new Intent((FenceMapActivity.this).getApplicationContext(), IntentServiceSearchFences.class);
//              intent.putExtra(IntentServiceSearchFences.SEARCHTEXT_EXTRA, newText);
//
//              intent.putExtra(IntentServiceGetFencesNearBy.PENDING_RESULT_EXTRA, fencesSearchReceiver);//result carrier object
//              FenceMapActivity.this.startService(intent);
//            }
//
//          }, 1000);//one sec
//        }
//        return true;
//      }
//    });

    return true;
  }

  public void setupFencesNearByServiceReceiver() {
    fencesNearByReceiver = new FencesNearByReceiver(new Handler(Looper.getMainLooper()));

    fencesNearByReceiver.setReceiver(new FencesNearByReceiver.Receiver() {
      @Override
      public void onReceiveResult(int resultCode, Bundle resultData) {
        if (resultCode == IntentServiceGetFencesNearBy.RESULT_CODE) {
          byte[] fencesNearByRaw = resultData.getByteArray(IntentServiceGetFencesNearBy.RESULT_RAW_RESPONSE_EXTRA);
          if (fencesNearByRaw != null) {
            try {
              SignalServiceProtos.FencesNearBy fencesNearBy = SignalServiceProtos.FencesNearBy.parseFrom(fencesNearByRaw);
              nearbyGroupsCount = fencesNearBy.getFencesCount();
              loadMarkers (fencesNearBy);

            } catch (InvalidProtocolBufferException ex) {
              Log.d(TAG, "onReceiveResult: "+ex.getMessage());
            }
          }else {
            Log.e(TAG, String.format("onReceiveResult: received null object"));
            nearbyGroupsCount = 0;
          }
        }else if (resultCode == IntentServiceGetFencesNearBy.EMPTYSET_CODE) {
          Log.e(TAG, String.format("onReceiveResult: received EMPTY SET"));
          nearbyGroupsCount = 0;
        }
      }
    });
  }

  public void setupFencesSearchServiceReceiver() {
    fencesSearchReceiver = new IntentServiceReceiver(new Handler(Looper.getMainLooper()));

    fencesSearchReceiver.setReceiver((resultCode, resultData) -> {
        if (resultCode == IntentServiceSearchFences.RESULT_CODE) {
          byte[] fencesSearchyRaw = resultData.getByteArray(IntentServiceSearchFences.RESULT_RAW_RESPONSE_EXTRA);
          if (fencesSearchyRaw != null) {
            try {
              SignalServiceProtos.FencesSearch fencesSearch = SignalServiceProtos.FencesSearch.parseFrom(fencesSearchyRaw);
              List<FenceSearchResultSuggestion> searchResults=adaptSearchResults(fencesSearch);
              mSearchView.swapSuggestions(searchResults);
              mSearchView.hideProgress();
            } catch (InvalidProtocolBufferException ex) {
              Log.d(TAG, "onReceiveResult: "+ex.getMessage());
            }
          } else {
            Log.e(TAG, String.format("onReceiveResult: received null object"));
          }
        } else if (resultCode == IntentServiceGetFencesNearBy.EMPTYSET_CODE) {
          Log.e(TAG, String.format("onReceiveResult: received EMPTY SET"));
        }
    });
  }

  //"Woronora:379423644163506195:Australia:New South Wales:Woronora:::151.035393:-34.026777"
  private List<FenceSearchResultSuggestion>adaptSearchResults(SignalServiceProtos.FencesSearch fencesSearch) {
    List<FenceSearchResultSuggestion> searchSuggestions = new LinkedList<>();
    int textSize = getResources().getDimensionPixelSize(R.dimen.suggestion_body_text_size);
    for (SignalServiceProtos.FencesSearch.RawResultRecord record: fencesSearch.getRawResultsList()) {
      searchSuggestions.add(new FenceSearchResultSuggestion(record.getRawPayload(), textSize));
    }

    return searchSuggestions;
  }

  private void updateCameraPosition(final LatLng updatedLatLng) {

    TextSecurePreferences.setFenceMapLastPositionLong(FenceMapActivity.this, (float)updatedLatLng.longitude);
    TextSecurePreferences.setFenceMapLastPositionLat(FenceMapActivity.this, (float)updatedLatLng.latitude);

    Timer timer = new Timer();
    timer.schedule(new TimerTask() {
      @Override
      public void run() {
        Intent intent = new Intent((FenceMapActivity.this), IntentServiceGetFencesNearBy.class);
        intent.putExtra(IntentServiceGetFencesNearBy.LONGITUDE_EXTRA, updatedLatLng.longitude);
        intent.putExtra(IntentServiceGetFencesNearBy.LATITUDE_EXTRA, updatedLatLng.latitude);

        intent.putExtra(IntentServiceGetFencesNearBy.PENDING_RESULT_EXTRA, fencesNearByReceiver);//result carrier object
        FenceMapActivity.this.startService(intent);
      }

    }, 1000);//one sec

    displayModel  = new BottomSheetDisplayModel(new LatLng(updatedLatLng.latitude, updatedLatLng.longitude));
    updateBottomSheetDisplayModel (null);
  }

  //based on CustomMarkerClusteringDemoActivity.java (potentially consider the newer CustomAdvancedMarkerClusteringDemoActivity.java v3.7.0
  private class MapableThingRenderer extends DefaultClusterRenderer<MapableThing> {
    private final IconGenerator iconGenerator        ;
    private final IconGenerator clusterIconGenerator ;
    private final ImageView imageView;
    private final ImageView clusterImageView;
    private final TextView label;
    private final int dimension;

    public MapableThingRenderer(Context context) {
      super(context, mMap, mClusterManager);
      iconGenerator        = new IconGenerator(context);
      clusterIconGenerator = new IconGenerator(context);
      View multiProfile = getLayoutInflater().inflate(R.layout.uf_map_multi_profile, null);
      clusterIconGenerator.setContentView(multiProfile);
      clusterImageView = multiProfile.findViewById(R.id.image);
      label = multiProfile.findViewById(R.id.amu_text);
      imageView = new ImageView(context);
      dimension = (int) getResources().getDimension(R.dimen.custom_profile_image);
      imageView.setLayoutParams(new ViewGroup.LayoutParams(dimension, dimension));
      int padding = (int) getResources().getDimension(R.dimen.custom_profile_padding);
      imageView.setPadding(padding, padding, padding, padding);
//      iconGenerator.setBackground(null);
      iconGenerator.setContentView(imageView);

    }

    @Override
    protected void onBeforeClusterItemRendered(@NonNull MapableThing mapable, MarkerOptions markerOptions) {
      markerOptions.icon(getItemIcon(mapable))
                   .title(((MapableGroup)mapable).getGroupRecord().getTitle());

    }

    @Override
    protected void onClusterItemUpdated(@NonNull MapableThing mapable, Marker marker) {
      // Same implementation as onBeforeClusterItemRendered() (to update cached markers)
      marker.setIcon(getItemIcon(mapable));
      marker.setTitle(((MapableGroup)mapable).getGroupRecord().getTitle());
    }

    @Override
    protected void onBeforeClusterRendered(Cluster<MapableThing> cluster, MarkerOptions markerOptions)
    {
      // Note: this method runs on the UI thread. Don't spend too much time in here (like in this example).
      markerOptions.icon(getClusterIcon(cluster));
    }

    @Override
    protected void onClusterUpdated(Cluster<MapableThing> cluster, Marker marker) {
      // Same implementation as onBeforeClusterRendered() (to update cached markers)
      marker.setIcon(getClusterIcon(cluster));
    }

    private BitmapDescriptor getItemIcon(MapableThing mapable) {
      imageView.setImageDrawable(new BitmapDrawable(getResources(), mapable.getAvatar()));
      Bitmap icon = iconGenerator.makeIcon();
      return BitmapDescriptorFactory.fromBitmap(icon);
    }

    /**
     * Get a descriptor for multiple people (a cluster) to be used for a marker icon. Note: this
     * method runs on the UI thread. Don't spend too much time in here (like in this example).
     *
     * @param cluster cluster to draw a BitmapDescriptor for
     * @return a BitmapDescriptor representing a cluster
     */
    private BitmapDescriptor getClusterIcon(Cluster<MapableThing> cluster) {
    List<Drawable> profilePhotos = new ArrayList<>(Math.min(4, cluster.getSize()));
    int width = dimension;
    int height = dimension;

    for (MapableThing p : cluster.getItems()) {
      // Draw 4 at most.
      if (profilePhotos.size() == 4) break;
      Drawable drawable = new BitmapDrawable(getResources(), p.getAvatar());
      drawable.setBounds(0, 0, width, height);
      profilePhotos.add(drawable);
    }
    MultiDrawable multiDrawable = new MultiDrawable(profilePhotos);
    multiDrawable.setBounds(0, 0, width, height);

    clusterImageView.setImageDrawable(multiDrawable);
    Bitmap icon = clusterIconGenerator.makeIcon(String.valueOf(cluster.getSize()));
    return BitmapDescriptorFactory.fromBitmap(icon);
  }

  @Override
  protected boolean shouldRenderAsCluster(Cluster cluster) {
    // Always render clusters.
    return cluster.getSize() > 1;
  }
 }

  @Override
  public boolean onClusterClick(Cluster<MapableThing> cluster) {
    clickedGroupRecord = null;

    // Zoom in the cluster, centring on LatLngBounds and including all the cluster items
    LatLngBounds.Builder builder = LatLngBounds.builder();
    for (ClusterItem item : cluster.getItems()) {
      builder.include(item.getPosition());
    }
    final LatLngBounds bounds = builder.build();

    try {
      mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100));
    } catch (Exception e) {
      e.printStackTrace();
    }

    updateBottomSheetDisplayModel(null);
    return true;
  }

  @Override
  public void onClusterInfoWindowClick(Cluster<MapableThing> cluster) {
    // Does nothing, but you could go to a list of the users.
  }

  @Override
  public boolean onClusterItemClick(MapableThing item) {
    clickedGroupRecord = ((MapableGroup)item).getGroupRecord();
    updateBottomSheetDisplayModel((MapableGroup)item);

    GlideApp.with(this)
            .load(item.getAvatar())
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .circleCrop()
            .into(fab);

    ArrayList<Drawable> drawablesBitmaps = new ArrayList<Drawable>() {{
      add(new BitmapDrawable(getResources(), item.getAvatar()));
    }};
    drawables = drawablesBitmaps;
    ItemPagerAdapter adapter = new ItemPagerAdapter(this, drawables);
    viewPager.setAdapter(adapter);

    mergedAppBarLayoutBehavior.setToolbarTitle(clickedGroupRecord!=null?clickedGroupRecord.getTitle():"*");

    return true;
  }

  @Override
  public void onClusterItemInfoWindowClick(MapableThing item) {
    // Does nothing, but you could go into the user's profile page, for example.
  }

  private void initialiseClustererManager()
  {
    mClusterManager = new ClusterManager<>(this, mMap);
    mClusterManager.setRenderer(new MapableThingRenderer(this));

    mMap.setOnMarkerClickListener(mClusterManager);
    mMap.setOnInfoWindowClickListener(mClusterManager);
    mClusterManager.setOnClusterClickListener(this);
    mClusterManager.setOnClusterInfoWindowClickListener(this);
    mClusterManager.setOnClusterItemClickListener(this);
    mClusterManager.setOnClusterItemInfoWindowClickListener(this);

    mClusterManager.cluster();

  }

  @Override
  public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
    mSearchView.setTranslationY(verticalOffset);
  }

  private void initialiseSearchResources()
  {
    mSearchView = findViewById(R.id.floating_search_view);
    mAppBar     = findViewById(R.id.appbarlayout);

    mAppBar.addOnOffsetChangedListener(this);

    initialiseFloatingSearchBar();

  }

  private void initialiseFloatingSearchBar() {
    mSearchView.setOnQueryChangeListener(new FloatingSearchView.OnQueryChangeListener() {

      @Override
      public void onSearchTextChanged(String oldQuery, final String newQuery) {

        if (!oldQuery.equals("") && newQuery.equals("")) {
          mSearchView.clearSuggestions();
        }
        else
        {
          //this shows the top left circular progress you can call it where ever you want, but
          //it makes sense to do it when loading something in the background.
          // mSearchView.showProgress();

          Timer timer = new Timer();
          timer.schedule(new TimerTask() {
            @Override
            public void run() {
              Intent intent = new Intent((FenceMapActivity.this), IntentServiceSearchFences.class);
              intent.putExtra(IntentServiceSearchFences.SEARCHTEXT_EXTRA, newQuery);

              intent.putExtra(IntentServiceGetFencesNearBy.PENDING_RESULT_EXTRA, fencesSearchReceiver);
              FenceMapActivity.this.startService(intent);
            }

          }, 1000);//one sec
        }

        Log.d(TAG, "onSearchTextChanged()");
      }
    });

    mSearchView.setOnSearchListener(new FloatingSearchView.OnSearchListener() {
      @Override
      public void onSuggestionClicked(final SearchSuggestion searchSuggestion) {
        Log.d(TAG, String.format("onSuggestionClicked(): %s", searchSuggestion.getBody()));

        LatLngBounds.Builder builder = LatLngBounds.builder();
        builder.include(((FenceSearchResultSuggestion) searchSuggestion).getLatLng());
        final LatLngBounds bounds = builder.build();

        try {
          mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100));
        } catch (Exception e) {
          e.printStackTrace();
        }

        Optional<MapableGroup> mapableGroup=MapableThingProvider.getMapbleGroup(((FenceSearchResultSuggestion)searchSuggestion).getFid());
        if (mapableGroup.isPresent())
        {
          clickedGroupRecord = mapableGroup.get().getGroupRecord();
          updateBottomSheetDisplayModel (mapableGroup.get());
        }

        //mLastQuery = searchSuggestion.getBody();
        mSearchView.clearSuggestions();
        mSearchView.clearSearchFocus();
        mSearchView.setSearchText(searchSuggestion.getBody());
      }

      @Override
      public void onSearchAction(String query) {
//        mLastQuery = query;
        final String userQuery=query;
        Timer timer = new Timer();
          timer.schedule(new TimerTask() {
            @Override
            public void run() {
              Intent intent = new Intent((FenceMapActivity.this), IntentServiceSearchFences.class);
              intent.putExtra(IntentServiceSearchFences.SEARCHTEXT_EXTRA, userQuery);

              intent.putExtra(IntentServiceGetFencesNearBy.PENDING_RESULT_EXTRA, fencesSearchReceiver);//result carrier object
              FenceMapActivity.this.startService(intent);
            }

          }, 1000);//one sec
        Log.d(TAG, "onSearchAction()");
      }
    });

    mSearchView.setOnFocusChangeListener(new FloatingSearchView.OnFocusChangeListener() {
      @Override
      public void onFocus() {
        //show suggestions when search bar gains focus (typically history suggestions)
//        mSearchView.swapSuggestions(DataHelper.getHistory(getActivity(), 3));

        Log.d(TAG, "onFocus()");
      }

      @Override
      public void onFocusCleared() {

        //set the title of the bar so that when focus is returned a new query begins
//        mSearchView.setSearchBarTitle(mLastQuery);

        //you can also set setSearchText(...) to make keep the query there when not focused and when focus returns
        //mSearchView.setSearchText(searchSuggestion.getBody());
//        int headerHeight = getResources().getDimensionPixelOffset(R.dimen.sliding_search_view_header_height);
//        ObjectAnimator anim = ObjectAnimator.ofFloat(mSearchView, "translationY",
//                                                     +                        0, headerHeight);
//        anim.setDuration(350);
//        anim.start();
//        fadeDimBackground(150, 0, null);
//
//        Log.d(TAG, "onFocusCleared()");
      }
    });

    mSearchView.setOnClearSearchActionListener(() ->  {
        Log.d(TAG, "onClearSearchClicked()");
        mSearchView.clearSuggestions();
    });

    mSearchView.setOnHomeActionClickListener(new FloatingSearchView.OnHomeActionClickListener() {
      @Override
      public void onHomeClicked() {
        onBackPressed();
      }
    });

    /*
     * Here you have access to the left icon and the text of a given suggestion
     * item after as it is bound to the suggestion list. You can utilize this
     * callback to change some properties of the left icon and the text. For example, you
     * can load the left icon images using your favorite image loading library, or change text color.
     *
     *
     * Important:
     * Keep in mind that the suggestion list is a RecyclerView, so views are reused for different
     * items in the list.
     */
    mSearchView.setOnBindSuggestionCallback(new SearchSuggestionsAdapter.OnBindSuggestionCallback() {
      @Override
      public void onBindSuggestion(View suggestionView, ImageView leftIcon,
                                   TextView textView, SearchSuggestion item, int itemPosition) {
//        ColorSuggestion colorSuggestion = (ColorSuggestion) item;
//
//        String textColor = mIsDarkSearchTheme ? "#ffffff" : "#000000";
//        String textLight = mIsDarkSearchTheme ? "#bfbfbf" : "#787878";
//
//        if (colorSuggestion.getIsHistory()) {
//          leftIcon.setImageDrawable(ResourcesCompat.getDrawable(getResources(),
//                                                                R.drawable.ic_history_black_24dp, null));
//
//          Util.setIconColor(leftIcon, Color.parseColor(textColor));
//          leftIcon.setAlpha(.36f);
//        } else {
//          leftIcon.setAlpha(0.0f);
//          leftIcon.setImageDrawable(null);
//        }
//
//        textView.setTextColor(Color.parseColor(textColor));
//        String text = colorSuggestion.getBody()
//                .replaceFirst(mSearchView.getQuery(),
//                              "<font color=\"" + textLight + "\">" + mSearchView.getQuery() + "</font>");
//        textView.setText(Html.fromHtml(text));
      }

    });
  }

  private void fadeDimBackground(int from, int to, Animator.AnimatorListener listener) {
    final long ANIM_DURATION = 350;
    ValueAnimator anim = ValueAnimator.ofInt(from, to);
    anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
      @Override
      public void onAnimationUpdate(ValueAnimator animation) {
        ColorDrawable mDimDrawable  = new ColorDrawable(Color.BLACK);
        int value = (Integer) animation.getAnimatedValue();
                  mDimDrawable.setAlpha(value);
      }
    });
    if (listener != null) {
      anim.addListener(listener);
    }
    anim.setDuration(ANIM_DURATION);
    anim.start();
}

  private void initialiseResources()
  {
    if (getLastKnownLocation().isEmpty()) {
      AlertDialog.Builder builder = new AlertDialog.Builder(this)
                                                   .setMessage(R.string.FenceMapActivity_unable_to_determine_your_current_location)
                                                   .setCancelable(false)
                                                   .setPositiveButton(android.R.string.ok, (d, which) -> {
                                                     d.dismiss();
                                                     finish();
                                                   });

      AlertDialog dialog = builder.create();
      dialog.getWindow().getAttributes().windowAnimations = R.style.DialogAnimation;
      dialog.show();
      return;
    }

    final LatLng myLatLng = getLastKnownLocation().get();
    displayModel  = new BottomSheetDisplayModel(myLatLng);

    fab = findViewById(R.id.fab);

    //
    CoordinatorLayout coordinatorLayout = findViewById(R.id.coordinatorlayout);
    View bottomSheet = coordinatorLayout.findViewById(R.id.bottom_sheet);
    final BottomSheetBehaviorGoogleMapsLike behavior = BottomSheetBehaviorGoogleMapsLike.from(bottomSheet);

    MergedAppBarLayout mergedAppBarLayout = findViewById(R.id.mergedappbarlayout);
    mergedAppBarLayoutBehavior = MergedAppBarLayoutBehavior.from(mergedAppBarLayout);
    mergedAppBarLayoutBehavior.setNavigationOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        behavior.setState(BottomSheetBehaviorGoogleMapsLike.STATE_ANCHOR_POINT);
      }
    });

    bottomSheetMainhead = bottomSheet.findViewById(R.id.bottom_sheet_mainhead);
    bottomSheetSubhead1 = bottomSheet.findViewById(R.id.bottom_sheet_subhead1);
    bottomSheetSubhead2 = bottomSheet.findViewById(R.id.bottom_sheet_subhead2);
    bottomSheetMainhead.setText(displayModel.getMainHeader());
    bottomSheetSubhead1.setText(displayModel.getSubHeader1());
    bottomSheetSubhead2.setText(displayModel.getSubHeader2());

    ItemPagerAdapter adapter = new ItemPagerAdapter(this, drawables);
    viewPager = findViewById(R.id.pager);
    viewPager.setAdapter(adapter);

    behavior.setState(BottomSheetBehaviorGoogleMapsLike.STATE_COLLAPSED);
    //

    mMapFragment = SupportMapFragment.newInstance();
    FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
    fragmentTransaction.add(R.id.map_container, mMapFragment, "map");
    fragmentTransaction.commit();
    mMapFragment.getMapAsync((googleMap) ->  {
        mMap = googleMap;
        googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(FenceMapActivity.this, R.raw.fence_map_style));
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myLatLng, 13));
        googleMap.setBuildingsEnabled(true);
        googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        googleMap.setMyLocationEnabled(true);
        //googleMap.getUiSettings().setAllGesturesEnabled(false);

        mDragTimer = new CountDownTimer(DRAG_TIMER_INTERVAL, DRAG_TIMER_INTERVAL + 1) {
          @Override
          public void onTick(long l) {

          }

          @Override
          public void onFinish() {
            mTimerIsRunning = false;
            //todo: at the end of timer we can load objects even if drag is still on
//            mPetrolStationsArray.setPosition(mPosition);
          }
        };

        mMap.setOnCameraMoveStartedListener((i) -> {
            mDragTimer.start();
            mTimerIsRunning = true;
        });

        ml.registerListener(mClusterManager);

        ml.registerListener(() -> {
            clickedGroupRecord = null;
            this.cameraPosition = mMap.getCameraPosition();
            mPosition = cameraPosition.target;
            updateCameraPosition(new LatLng(mPosition.latitude, mPosition.longitude));

            // Cleaning all the markers.
//            if (mMap != null) {
//              mMap.clear();
//            }

            mPosition = mMap.getCameraPosition().target;
            mZoom = mMap.getCameraPosition().zoom;

            if (mTimerIsRunning) {
              mDragTimer.cancel();
            }

        });

        mMap.setOnCameraIdleListener(ml);

        initialiseClustererManager();

        googleMap.setOnMapLoadedCallback(()-> {
        });
    });

  }

  private void askForPermissionIfNeededAndLaunch() {
    if (!Permissions.hasAll(this, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
      Permissions.with(this)
                 .request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.unfacd_needs_access_to_your_location_to_allow_discovery_of_local_groups_and_events), R.drawable.ic_explore_white_48dp)
                 .onAnyDenied(this::finish)
                 .onAllGranted(this::initialiseMapAndResources)
                 .execute();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  private void loadMarkers(SignalServiceProtos.FencesNearBy fencesNearBy)
  {
    if (fencesNearBy.getFencesCount() > 0) {
      if (mMap != null) {
        //mMap.clear();
      }

      for (SignalServiceProtos.FenceRecord fenceRecord: fencesNearBy.getFencesList()) {
        if (fenceRecord.hasAvatar())  Log.d(TAG, String.format(Locale.getDefault(), "loadMarkers (fid:'%d'): FOUND AVATAR '%s'...", fenceRecord.getFid(), fenceRecord.getAvatar().getId()));

        MapableThing mapableThing = MapableThingProvider.getMapbleGroup(this, fenceRecord);
        mClusterManager.addItem(mapableThing);
        mapableThing.addListener(this);
      }
      mClusterManager.cluster();
    }
  }

  private LatLng getLocation(SignalServiceProtos.LocationRecord locationRecord)
  {
    return new LatLng(locationRecord.getLatitude(), locationRecord.getLongitude());
  }

  private Optional<LatLng> getLastKnownLocation()
  {
    float longitude = TextSecurePreferences.getFenceMapLastPositionLong(this);
    float latitude  = TextSecurePreferences.getFenceMapLastPositionLat(this);
    if (longitude != 0 && latitude != 0)  return Optional.of(new LatLng(latitude, longitude));

    ufLocation myLocation = ufLocation.getInstance();
    if (myLocation.isCurrentLocationKnown())  return Optional.of(new LatLng(myLocation.getCurrentLocation().getLatitude(), myLocation.getCurrentLocation().getLongitude()));

    return Optional.empty();
  }

  @Override
  public void onModified(MapableThing mapableThing)
  {
    Log.d(TAG, String.format("onModified(%s): MapableThing changed...", ((MapableGroup)mapableThing).getGroupRecord().getAvatarUfId()));
   Collection<Marker> markers = mClusterManager.getMarkerCollection().getMarkers();
   if (markers.contains(mapableThing)) {
     Log.d(TAG, String.format("onModified(%s): Found MapableThing...", ((MapableGroup)mapableThing).getGroupRecord().getAvatarUfId()));
   }
  }

  private void updateBottomSheetDisplayModel(MapableGroup mapableGroup)
  {
    bottomSheetMainhead.setText(displayModel.getMainHeader());
    bottomSheetSubhead1.setText(displayModel.getSubHeader1());
    bottomSheetSubhead2.setText(displayModel.getSubHeader2());
  }

  private class BottomSheetDisplayModel
  {
    private LatLng currentLatLng;
    private Location mylocation = ufLocation.getInstance().getMyLocation();

    public BottomSheetDisplayModel(LatLng currentLatLng) {
      this.currentLatLng = currentLatLng;
    }

    public String getMainHeader()
    {
      if (clickedGroupRecord != null) {
        return clickedGroupRecord.getTitle();
      } else if (ufLocation.getInstance().getMyAddress() != null) {

        return TextUtils.isEmpty(ufLocation.getInstance().getMyAddress().getLocality())
               ? "*"
               : ufLocation.getInstance().getMyAddress().getLocality();
      }

      return "*";
    }

    public String getSubHeader1()
    {
      StringBuilder description = new StringBuilder();

      double distanceInMeters=SphericalUtil.computeDistanceBetween(new LatLng(mylocation.getLatitude(), mylocation.getLongitude()), currentLatLng);
      if (distanceInMeters<1000) description.insert(0, String.format(Locale.getDefault(), "Nearly %d m from your homebase...", (int)distanceInMeters));
      else description.insert(0, String.format(Locale.getDefault(), "Nearly %d km from your homebase...", (int)distanceInMeters/1000));

      return description.toString();
    }

    public String getSubHeader2 ()
    {
      StringBuilder description = new StringBuilder();

      if (nearbyGroupsCount>0)  description.insert(0, String.format(Locale.getDefault(), "Explore %d groups around your current location", nearbyGroupsCount));
      else                      description.insert(0, String.format(Locale.getDefault(), "No groups found around your current location"));

      return description.toString();
    }
  }

  //AA A Composite hack to enable multi listener since clustermanager wants to listen on idle as well as us
  public class IdleCameraMultiListener implements GoogleMap.OnCameraIdleListener {
    private List<GoogleMap.OnCameraIdleListener> mListeners = new ArrayList<GoogleMap.OnCameraIdleListener>();

    public void registerListener (GoogleMap.OnCameraIdleListener listener) {
      mListeners.add(listener);
    }

    @Override
    public void onCameraIdle()
    {
      for (GoogleMap.OnCameraIdleListener ccl: mListeners)
      {
        if (ccl != null)  ccl.onCameraIdle();
      }
    }

  }
}

