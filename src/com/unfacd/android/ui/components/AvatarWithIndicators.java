package com.unfacd.android.ui.components;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.appcompat.widget.AppCompatImageView;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.unfacd.android.R;

import org.whispersystems.libsignal.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.unfacd.android.ui.components.AvatarWithIndicators.EnumAvatarIndicators.BOTTOM_LEFT;
import static com.unfacd.android.ui.components.AvatarWithIndicators.EnumAvatarIndicators.BOTTOM_RIGHT;
import static com.unfacd.android.ui.components.AvatarWithIndicators.EnumAvatarIndicators.CENTRE_BOTTOM;
import static com.unfacd.android.ui.components.AvatarWithIndicators.EnumAvatarIndicators.CENTRE_TOP;
import static com.unfacd.android.ui.components.AvatarWithIndicators.EnumAvatarIndicators.MIDDLE_LEFT;
import static com.unfacd.android.ui.components.AvatarWithIndicators.EnumAvatarIndicators.MIDDLE_RIGHT;
import static com.unfacd.android.ui.components.AvatarWithIndicators.EnumAvatarIndicators.TOP_RIGHT;
import static com.unfacd.android.ui.components.AvatarWithIndicators.EnumAvatarIndicators.TOP_LEFT;

public class AvatarWithIndicators extends AppCompatImageView
{
  private static final float CORNER_OFFSET = 12F;
  private static final String[] POSITIONS  = new String[] {"bottom_right"};

  public enum EnumAvatarIndicators
  {
    TOP_RIGHT(0),
    MIDDLE_RIGHT(1), //user can change: group name, banner,
    BOTTOM_RIGHT(2), //user can change group membership: invite others, ban, kick
    CENTRE_BOTTOM(3),
    BOTTOM_LEFT(4),
    MIDDLE_LEFT(5),
    TOP_LEFT(6),
    CENTRE_TOP(7);

    private int value;

    EnumAvatarIndicators(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  final private Map<EnumAvatarIndicators, IndicatorDrawable> indicatorDrawables = new HashMap<>();
  static final private ArrayList<Integer> topRightResources= new ArrayList<>(Arrays.asList(R.attr.avatar_crown, R.attr.avatar_heart));
  static final private ArrayList<Integer> middleRightResources=new ArrayList<>(Arrays.asList(R.attr.avatar_crown));
  static final private ArrayList<Integer> bottomRightResources=new ArrayList<>(Arrays.asList(R.attr.avatar_broadcast));
  static final private ArrayList<Integer>  centreBottomResources=new ArrayList<>(Arrays.asList(R.attr.avatar_crown));
  static final private ArrayList<Integer>  botomLeftResources=new ArrayList<>(Arrays.asList(R.attr.avatar_crown));
  static final private ArrayList<Integer>  middleLeftResources=new ArrayList<>(Arrays.asList(R.attr.avatar_crown));
  static final private ArrayList<Integer>  topLeftResources=new ArrayList<>(Arrays.asList(R.attr.avatar_compass));
  static final private ArrayList<Integer>  centretTopResources=new ArrayList<>(Arrays.asList(R.attr.avatar_crown));

  static final private Map<EnumAvatarIndicators, Pair<ArrayList<Integer>, Integer>> drawableResources;
    static {
    Map<EnumAvatarIndicators, Pair<ArrayList<Integer>, Integer>> drawablesResourcesMap = new HashMap<>();

      drawablesResourcesMap.put(TOP_RIGHT, new Pair<>(topRightResources, R.color.uf_primary_dark));
      drawablesResourcesMap.put(MIDDLE_RIGHT, new Pair<>(middleRightResources, R.color.uf_primary_dark));
      drawablesResourcesMap.put(BOTTOM_RIGHT, new Pair<>(bottomRightResources, R.color.uf_primary_dark));
      drawablesResourcesMap.put(CENTRE_BOTTOM,new Pair<>(centreBottomResources, R.color.uf_primary_dark));
      drawablesResourcesMap.put(BOTTOM_LEFT, new Pair<>(botomLeftResources, R.color.uf_primary_dark));
      drawablesResourcesMap.put(MIDDLE_LEFT, new Pair<>(middleLeftResources, R.color.uf_primary_dark));
      drawablesResourcesMap.put(TOP_LEFT,   new Pair<>(topLeftResources, R.color.uf_primary_dark));
      drawablesResourcesMap.put(CENTRE_TOP, new Pair<>(centretTopResources, R.color.uf_primary_dark));

      drawableResources = Collections.unmodifiableMap(drawablesResourcesMap);
  }

  static final private Map<EnumAvatarIndicators, BoundComputor> boundComputors;
  static {
    Map<EnumAvatarIndicators, BoundComputor> drawablesMap = new HashMap<>();

    //calculations for left, right, top, bottom are based on absolute values for width and height
    //width == right - left, and height == bottom - top.
    drawablesMap.put(TOP_RIGHT, new BoundComputor() {
      public void computeBounds (Canvas c, View view, IndicatorDrawable indicatorDrawable) {
        final int right = view.getWidth();
        final int bottom = view.getHeight();
        indicatorDrawable.getDrawable().setBounds(
                right - indicatorDrawable.getDrawableIntrinsicWidth(),
                0,
                right,
                indicatorDrawable.getDrawableIntrinsicHeight()
                );
      }
    });

    drawablesMap.put(TOP_LEFT, new BoundComputor() {
      public void computeBounds (Canvas c, View view, IndicatorDrawable indicatorDrawable) {
        final int right = view.getWidth();
        final int bottom = view.getHeight();
        indicatorDrawable.getDrawable().setBounds(
                0,
                0,
                indicatorDrawable.getDrawableIntrinsicWidth(),
                indicatorDrawable.getDrawableIntrinsicHeight()
        );
      }
    });

    drawablesMap.put(CENTRE_TOP, new BoundComputor() {
      public void computeBounds (Canvas c, View view, IndicatorDrawable indicatorDrawable) {
        final int right = view.getWidth();
        final int bottom = view.getHeight();
        indicatorDrawable.getDrawable().setBounds(
                right-((right/2) + indicatorDrawable.getDrawableIntrinsicWidth()/2),
                0,
                right-((right/2) - indicatorDrawable.getDrawableIntrinsicWidth()/2),
                indicatorDrawable.getDrawableIntrinsicHeight()
        );
      }
    });

    drawablesMap.put(EnumAvatarIndicators.MIDDLE_RIGHT, new BoundComputor() {
      public void computeBounds (Canvas c, View view, IndicatorDrawable indicatorDrawable) {
        final int right = view.getWidth();
        final int bottom = view.getHeight();
        indicatorDrawable.getDrawable().setBounds(
                right - indicatorDrawable.getDrawableIntrinsicWidth(),
                bottom-((bottom/2) + indicatorDrawable.getDrawableIntrinsicHeight()/2),
                right,
                bottom-((bottom/2) - indicatorDrawable.getDrawableIntrinsicHeight()/2));
      }
    });

    drawablesMap.put(BOTTOM_RIGHT, new BoundComputor() {
      public void computeBounds (Canvas c, View view, IndicatorDrawable indicatorDrawable) {
        final int right = view.getWidth();
        final int bottom = view.getHeight();
        indicatorDrawable.getDrawable().setBounds(
                right - indicatorDrawable.getDrawableIntrinsicWidth(),
                bottom - indicatorDrawable.getDrawableIntrinsicHeight(),
                right,
                bottom);
      }
    });


    boundComputors = Collections.unmodifiableMap(drawablesMap);
  }

  private int position;
  private float density;

  public AvatarWithIndicators(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    initialize(attrs);
  }

  public AvatarWithIndicators(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize(attrs);
  }

  public AvatarWithIndicators(Context context) {
    super(context);
    initialize(null);
  }

  private void initialize(AttributeSet attrs) {

    density = getContext().getResources().getDisplayMetrics().density;
  }


  public void setIndicator (EnumAvatarIndicators indicator, int index) {
    IndicatorDrawable indicatorDrawable = indicatorDrawables.get(indicator);
    if (indicatorDrawable==null)
    {
      indicatorDrawable = new IndicatorDrawable(drawableResources.get(indicator).first().get(index));
      indicatorDrawables.put(indicator, indicatorDrawable);

      int attributes[] = new int[]{indicatorDrawable.getResource()};
      TypedArray drawables = getContext().obtainStyledAttributes(attributes);
      Drawable drawable = drawables.getDrawable(0);
      drawable = DrawableCompat.wrap(drawable);
      DrawableCompat.setTint(drawable.mutate(), getResources().getColor(drawableResources.get(indicator).second()));
      indicatorDrawable.setDrawable(drawable);

      indicatorDrawable.setDrawableIntrinsicWidth(drawable.getIntrinsicWidth());
      indicatorDrawable.setDrawableIntrinsicHeight(drawable.getIntrinsicHeight());

      indicatorDrawable.setBoundComputor(boundComputors.get(indicator));

      drawables.recycle();
    }
  }

  public void setIndicator (EnumAvatarIndicators indicator) {
    setIndicator (indicator, 0);
  }

  @Override
  public void onDraw(Canvas c) {
    super.onDraw(c);
    c.save();

    for (Map.Entry<EnumAvatarIndicators, IndicatorDrawable> entry : indicatorDrawables.entrySet()) {
      if (entry.getValue().getDrawable()!=null) {
        entry.getValue().getBoundComputor().computeBounds(c, this, entry.getValue());
        entry.getValue().getDrawable().draw(c);
      }
    }

    c.restore();
  }

  public void setPosition(int position) {
    this.position = position;
//    setIndicator();
    invalidate();
  }

  public int getPosition() {
    return position;
  }

  public float getCloseOffset() {
    return CORNER_OFFSET * density;
  }

  public ImageView asImageView() {
    return this;
  }

//  public float getFarOffset() {
//    return getCloseOffset() + drawableIntrinsicHeight;
//  }

  public float getFarOffset(EnumAvatarIndicators indicator) {
    return getCloseOffset() + indicatorDrawables.get(indicator).getDrawableIntrinsicHeight();
  }

  private static class IndicatorDrawable {
    private int resource;
    private int drawableIntrinsicWidth;
    private int drawableIntrinsicHeight;
    private Drawable drawable;
    private BoundComputor boundComputor;

    public IndicatorDrawable (int resource)
    {
      this.resource=resource;
    }

    public IndicatorDrawable (Drawable drawable, int resource)
    {
      this.drawable=drawable;
      this.resource=resource;
    }

    public Drawable getDrawable ()
    {
      return drawable;
    }

    public void setDrawable (Drawable drawable)
    {
      this.drawable = drawable;
    }

    public int getResource ()
    {
      return resource;
    }

    public int getDrawableIntrinsicWidth ()
    {
      return drawableIntrinsicWidth;
    }


    public BoundComputor getBoundComputor ()
    {
      return boundComputor;
    }


    public void setDrawableIntrinsicWidth (int drawableIntrinsicWidth)
    {
      this.drawableIntrinsicWidth = drawableIntrinsicWidth;
    }

    public int getDrawableIntrinsicHeight ()
    {
      return drawableIntrinsicHeight;
    }

    public void setDrawableIntrinsicHeight (int drawableIntrinsicHeight)
    {
      this.drawableIntrinsicHeight = drawableIntrinsicHeight;
    }


    public void setBoundComputor (BoundComputor boundComputor)
    {
      this.boundComputor = boundComputor;
    }
  }

  interface BoundComputor {
    void computeBounds(Canvas c, View view, IndicatorDrawable indicatorDrawable);
  }
}
