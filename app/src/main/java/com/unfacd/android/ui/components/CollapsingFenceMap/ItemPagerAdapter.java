package com.unfacd.android.ui.components.CollapsingFenceMap;

import  com.unfacd.android.R;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;

import androidx.viewpager.widget.PagerAdapter;

public class ItemPagerAdapter extends PagerAdapter
{

  Context mContext;
  LayoutInflater mLayoutInflater;
//  final int[] mItems;
  ArrayList<Drawable> drawables;

  public ItemPagerAdapter(Context context, ArrayList<Drawable> drawables) {
    this.mContext = context;
    this.mLayoutInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    this.drawables = drawables;
  }

  @Override
  public int getCount() {
//    return mItems.length;
    return drawables.size();
  }

  @Override
  public boolean isViewFromObject(View view, Object object) {
    return view == ((LinearLayout) object);
  }

  @Override
  public Object instantiateItem(ViewGroup container, int position) {
    View itemView = mLayoutInflater.inflate(R.layout.uf_fence_map_pager_item, container, false);
    ImageView imageView = itemView.findViewById(R.id.imageView);
//    imageView.setImageResource(mItems[position]);
    imageView.setImageDrawable(drawables.get(position));
    container.addView(itemView);
    return itemView;
  }

  @Override
  public void destroyItem(ViewGroup container, int position, Object object) {
    container.removeView((LinearLayout) object);
  }
}
